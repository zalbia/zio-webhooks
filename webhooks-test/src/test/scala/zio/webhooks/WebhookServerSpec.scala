package zio.webhooks

import zio._
import zio.clock.Clock
import zio.duration._
import zio.magic._
import zio.stream._
import zio.test.Assertion._
import zio.test.DefaultRunnableSpec
import zio.test.TestAspect._
import zio.test._
import zio.test.environment._
import zio.webhooks.WebhookError._
import zio.webhooks.WebhookServer.BatchingConfig
import zio.webhooks.WebhookServerSpecUtil._
import zio.webhooks.testkit._

import java.time.Instant

object WebhookServerSpec extends DefaultRunnableSpec {
  def spec =
    suite("WebhookServerSpec")(
      suite("on new event subscription")(
        suite("with single dispatch")(
          testM("dispatches correct request given event") {
            val webhook = singleWebhook(0, WebhookStatus.Enabled, WebhookDeliveryMode.SingleAtMostOnce)

            val event = WebhookEvent(
              WebhookEventKey(WebhookEventId(0), webhook.id),
              WebhookEventStatus.New,
              "event payload",
              jsonContentHeaders
            )

            val expectedRequest = WebhookHttpRequest(webhook.url, event.content, event.headers)

            webhooksTestScenario(
              stubResponses = List(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = List(event),
              requestsAssertion = requests => assertM(requests.runHead)(isSome(equalTo(expectedRequest)))
            )
          },
          testM("event is marked Delivering, then Delivered on successful dispatch") {
            val webhook = singleWebhook(0, WebhookStatus.Enabled, WebhookDeliveryMode.SingleAtMostOnce)

            val event = WebhookEvent(
              WebhookEventKey(WebhookEventId(0), webhook.id),
              WebhookEventStatus.New,
              "event payload",
              jsonContentHeaders
            )

            val expectedStatuses = List(WebhookEventStatus.Delivering, WebhookEventStatus.Delivered)

            webhooksTestScenario(
              stubResponses = List(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = List(event),
              eventsAssertion = events => {
                val eventStatuses = events.filter(!_.status.isNew).take(2).map(_.status).runCollect
                assertM(eventStatuses)(hasSameElements(expectedStatuses))
              }
            )
          },
          testM("can dispatch single event to n webhooks") {
            val n                 = 100
            val webhooks          = createWebhooks(n)(WebhookStatus.Enabled, WebhookDeliveryMode.SingleAtMostOnce)
            val eventsToNWebhooks = webhooks.map(_.id).flatMap(webhook => createPlaintextEvents(1)(webhook))

            webhooksTestScenario(
              stubResponses = List.fill(n)(WebhookHttpResponse(200)),
              webhooks = webhooks,
              events = eventsToNWebhooks,
              requestsAssertion = _.take(n.toLong).runDrain *> assertCompletesM
            )
          },
          testM("dispatches no events for disabled webhooks") {
            val n       = 100
            val webhook = singleWebhook(0, WebhookStatus.Disabled, WebhookDeliveryMode.SingleAtMostOnce)

            webhooksTestScenario(
              stubResponses = List.fill(n)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = createPlaintextEvents(n)(webhook.id),
              requestsAssertion =
                requests => assertM(requests.take(1).timeout(50.millis).runHead.provideLayer(Clock.live))(isNone)
            )
          },
          testM("dispatches no events for unavailable webhooks") {
            val n       = 100
            val webhook =
              singleWebhook(0, WebhookStatus.Unavailable(Instant.EPOCH), WebhookDeliveryMode.SingleAtMostOnce)

            webhooksTestScenario(
              stubResponses = List.fill(n)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = createPlaintextEvents(n)(webhook.id),
              requestsAssertion =
                requests => assertM(requests.take(1).timeout(50.millis).runHead.provideLayer(Clock.live))(isNone)
            )
          },
          testM("doesn't batch with disabled batching config ") {
            val n       = 100
            val webhook = singleWebhook(id = 0, WebhookStatus.Enabled, WebhookDeliveryMode.BatchedAtMostOnce)

            webhooksTestScenario(
              stubResponses = List.fill(n)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = createPlaintextEvents(n)(webhook.id),
              requestsAssertion = _.take(n.toLong).runDrain *> assertCompletesM,
              sleepDuration = 100.millis
            )
          },
          testM("missing webhook errors are sent to stream") {
            val idRange               = 401L to 404L
            val missingWebhookIds     = idRange.map(WebhookId(_))
            val eventsMissingWebhooks = missingWebhookIds.flatMap(id => createPlaintextEvents(1)(id))

            val expectedErrorCount = missingWebhookIds.size

            webhooksTestScenario(
              stubResponses = List(WebhookHttpResponse(200)),
              webhooks = List.empty,
              events = eventsMissingWebhooks,
              errorsAssertion = errors =>
                assertM(errors.take(expectedErrorCount.toLong).runCollect)(
                  hasSameElements(idRange.map(id => MissingWebhookError(WebhookId(id))))
                )
            )
          }
        ).provideCustomLayer(testEnv(BatchingConfig.disabled)),
        suite("with batched dispatch")(
          testM("batches events by max batch size") {
            val n            = 100
            val maxBatchSize = 10
            val webhook      = singleWebhook(id = 0, WebhookStatus.Enabled, WebhookDeliveryMode.BatchedAtMostOnce)

            val expectedRequestsMade = n / maxBatchSize

            webhooksTestScenario(
              stubResponses = List.fill(n)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = createPlaintextEvents(n)(webhook.id),
              requestsAssertion = _.take(expectedRequestsMade.toLong).runDrain *> assertCompletesM
            )
          },
          testM("batches for multiple webhooks") {
            val eventCount   = 100
            val webhookCount = 10
            val maxBatchSize = eventCount / webhookCount // 10
            val webhooks     = createWebhooks(webhookCount)(
              WebhookStatus.Enabled,
              WebhookDeliveryMode.BatchedAtMostOnce
            )
            val events       = webhooks.map(_.id).flatMap(webhook => createPlaintextEvents(maxBatchSize)(webhook))

            val expectedRequestsMade = maxBatchSize

            webhooksTestScenario(
              stubResponses = List.fill(eventCount)(WebhookHttpResponse(200)),
              webhooks = webhooks,
              events = events,
              requestsAssertion = _.take(expectedRequestsMade.toLong).runDrain *> assertCompletesM
            )
          },
          testM("events dispatched by batch are marked delivered") {
            val eventCount = 100
            val webhook    = singleWebhook(id = 0, WebhookStatus.Enabled, WebhookDeliveryMode.BatchedAtMostOnce)

            webhooksTestScenario(
              stubResponses = List.fill(10)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = createPlaintextEvents(eventCount)(webhook.id),
              eventsAssertion =
                _.filter(_.status == WebhookEventStatus.Delivered).take(eventCount.toLong).runDrain *> assertCompletesM
            )
          },
          testM("doesn't batch before max wait time") {
            val n       = 5 // less than max batch size 10
            val webhook = singleWebhook(id = 0, WebhookStatus.Enabled, WebhookDeliveryMode.BatchedAtMostOnce)
            val events  = createPlaintextEvents(n)(webhook.id)

            val expectedRequestsMade = 0

            webhooksTestScenario(
              stubResponses = List.fill(n)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = events,
              requestsAssertion = _.take(expectedRequestsMade.toLong).runDrain *> assertCompletesM,
              adjustDuration = Some(2.seconds)
            )
          },
          testM("batches on max wait time") {
            val n       = 5 // less than max batch size 10
            val webhook = singleWebhook(id = 0, WebhookStatus.Enabled, WebhookDeliveryMode.BatchedAtMostOnce)
            val events  = createPlaintextEvents(n)(webhook.id)

            val expectedRequestsMade = 1

            webhooksTestScenario(
              stubResponses = List.fill(n)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = events,
              requestsAssertion = _.take(expectedRequestsMade.toLong).runDrain *> assertCompletesM,
              adjustDuration = Some(5.seconds)
            )
          },
          testM("batches events on webhook and content-type") {
            val webhook = singleWebhook(id = 0, WebhookStatus.Enabled, WebhookDeliveryMode.BatchedAtMostOnce)

            val jsonEvents      = createJsonEvents(4)(webhook.id)
            val plaintextEvents = createPlaintextEvents(4)(webhook.id)

            webhooksTestScenario(
              stubResponses = List.fill(2)(WebhookHttpResponse(200)),
              webhooks = List(webhook),
              events = jsonEvents ++ plaintextEvents,
              requestsAssertion = _.take(2).runDrain *> assertCompletesM,
              adjustDuration = Some(5.seconds)
            )
          }
        ).provideCustomLayer(testEnv(BatchingConfig.default))
        // TODO: test that after 7 days have passed since webhook event delivery failure, a webhook is set unavailable
      )
    ) @@ nonFlaky @@ timeout(2.minutes)
}

object WebhookServerSpecUtil {

  def createWebhooks(n: Int)(status: WebhookStatus, deliveryMode: WebhookDeliveryMode): Iterable[Webhook] =
    (0 until n).map(i => singleWebhook(i.toLong, status, deliveryMode))

  def createJsonEvents(n: Int)(webhookId: WebhookId): Iterable[WebhookEvent] =
    (0 until n).map { i =>
      WebhookEvent(
        WebhookEventKey(WebhookEventId(i.toLong), webhookId),
        WebhookEventStatus.New,
        s"""{"event":"payload$i"}""",
        Chunk(("Accept", "*/*"), ("Content-Type", "application/json"))
      )
    }

  def createPlaintextEvents(n: Int)(webhookId: WebhookId): Iterable[WebhookEvent] =
    (0 until n).map { i =>
      WebhookEvent(
        WebhookEventKey(WebhookEventId(i.toLong), webhookId),
        WebhookEventStatus.New,
        "event payload " + i,
        Chunk(("Accept", "*/*"), ("Content-Type", "text/plain"))
      )
    }

  val jsonContentHeaders = Chunk(("Accept", "*/*"), ("Content-Type", "application/json"))

  def singleWebhook(id: Long, status: WebhookStatus, deliveryMode: WebhookDeliveryMode): Webhook =
    Webhook(
      WebhookId(id),
      "http://example.org/" + id,
      "testWebhook" + id,
      status,
      deliveryMode
    )

  type SpecEnv = Has[WebhookEventRepo]
    with Has[TestWebhookEventRepo]
    with Has[WebhookRepo]
    with Has[TestWebhookRepo]
    with Has[WebhookStateRepo]
    with Has[TestWebhookHttpClient]
    with Has[WebhookHttpClient]
    with Has[Option[BatchingConfig]]
    with Has[WebhookServer]

  def testEnv(batchingConfig: ULayer[Has[Option[BatchingConfig]]]): URLayer[Clock, SpecEnv] =
    ZLayer
      .fromSomeMagic[Clock, SpecEnv](
        TestWebhookRepo.test,
        TestWebhookEventRepo.test,
        TestWebhookStateRepo.test,
        TestWebhookHttpClient.test,
        batchingConfig,
        WebhookServer.live
      )
      .orDie

  def webhooksTestScenario(
    stubResponses: Iterable[WebhookHttpResponse],
    webhooks: Iterable[Webhook],
    events: Iterable[WebhookEvent],
    errorsAssertion: UStream[WebhookError] => UIO[TestResult] = _ => assertCompletesM,
    eventsAssertion: UStream[WebhookEvent] => UIO[TestResult] = _ => assertCompletesM,
    requestsAssertion: UStream[WebhookHttpRequest] => UIO[TestResult] = _ => assertCompletesM,
    adjustDuration: Option[Duration] = None,
    sleepDuration: Duration = 64.millis
  ): RIO[SpecEnv with TestClock, TestResult] =
    for {
      errorsFiber   <- ZIO.service[WebhookServer].flatMap(server => errorsAssertion(server.getErrors).fork)
      requestsFiber <- TestWebhookHttpClient.requests.flatMap(requestsAssertion(_).fork)
      eventsFiber   <- TestWebhookEventRepo.getEvents.flatMap(eventsAssertion(_).fork)
      // let test fiber sleep to give time for fiber streams to come online
      // TODO[low-prio]: for optimization, see ZQueueSpec wait for value pattern
      _             <- Clock.Service.live.sleep(sleepDuration)
      responseQueue <- Queue.unbounded[WebhookHttpResponse]
      _             <- responseQueue.offerAll(stubResponses)
      _             <- TestWebhookHttpClient.setResponse(_ => Some(responseQueue))
      _             <- ZIO.foreach_(webhooks)(TestWebhookRepo.createWebhook(_))
      _             <- ZIO.foreach_(events)(TestWebhookEventRepo.createEvent(_))
      _             <- adjustDuration.map(TestClock.adjust(_)).getOrElse(ZIO.unit)
      tests         <- Fiber.collectAll(List(errorsFiber, requestsFiber, eventsFiber)).join
    } yield tests.foldLeft(assertCompletes)(_ && _)
}
