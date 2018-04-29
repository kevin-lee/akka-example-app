package io.kevinlee.akka.example

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.{Http, HttpExt}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.kevinlee.akka.example.WebPageCollector.{Collect, ProcessNext}
import io.kevinlee.akka.example.WordCountMainActor.{Count, WebPageContent, WebPageWordCounted}
import org.jsoup.Jsoup

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
  * @author Kevin Lee
  * @since 2018-02-17
  */
object WordCountApp extends App {
  val system = ActorSystem("wordCountApp")

  val wordCountMainActor = system.actorOf(WordCountMainActor.props)

  wordCountMainActor ! Count(InputValues.websites)

}

class WordCountMainActor extends Actor with ActorLogging {
  implicit val system = context.system
  var start = 0L
  var numberOfPages = 0
  var results = Vector.empty[(String, Seq[(String, Int)])]

  var router = {
    val routers = Vector.fill(20) {
      val webPageCollector = context.actorOf(WebPageCollector.props(Http()))
      context watch webPageCollector
      ActorRefRoutee(webPageCollector)
    }
    Router(RoundRobinRoutingLogic(), routers)
  }

  override def receive: Receive = {
    case Count(urls) =>
      numberOfPages = urls.length
      start = System.currentTimeMillis()

      urls.foreach { url =>
        // create WebPageCollector. It is not WebPageCollector type but ActorRef type.
//        val webPageCollector = context.actorOf(WebPageCollector.props(Http()))
        router.route(Collect(url), self)
      }

    case WebPageContent(url, content) =>
      log.info(
        s""">>>
           |    url: $url
           |content: $content
           |<<<""".stripMargin)
      // send content to WordCounter
      val wordCounter = context.actorOf(WordCounter.props)
      wordCounter ! WordCounter.Count(url, content)

    case WebPageWordCounted(url, wordsAndCounts) =>
      numberOfPages -= 1
      results = results :+ (url, wordsAndCounts)

      if (numberOfPages == 0) {
        val howLong = System.currentTimeMillis() - start
        println(
          s"""
             |           url: $url
             | numberOfPages: $numberOfPages
             |wordsAndCounts: ${results.mkString("\n============\n")}
             |""".stripMargin)
        println(s"It took $howLong ms.")
      }

      context.stop(sender)

    case Terminated(actor) =>
      router = router.removeRoutee(actor)
      val webPageCollector = context.actorOf(WebPageCollector.props(Http()))
      context watch webPageCollector
      router.addRoutee(webPageCollector)
  }
}

object WordCountMainActor {
  sealed trait Command
  case class Count(urls: List[String]) extends Command

  sealed trait Result
  case class WebPageContent(url: String, content: String) extends Result
  case class WebPageWordCounted(url: String, wordsAndCount: Seq[(String, Int)]) extends Result

  def props: Props = Props(new WordCountMainActor())
}

class WebPageCollector(http: HttpExt) extends Actor
                                         with ActorLogging {
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = context.system.dispatcher

  implicit val materializer = ActorMaterializer()

  /*
   * FIXME: The current solution has an obvious bug in it.
   * It will be fixed in next Live!
   */
  var currentUrl: Option[(String, ActorRef)] = None
  var urls = Vector.empty[(String, ActorRef)]

  var count = 0

  private def processUrl: Unit = urls match {
    case head +: _ =>
      val (url, theSender) = head
      currentUrl = Some(url, theSender)
      urls = urls.tail

      println(s"$url - $sender")

      // access the web page and collect it
      val responseFuture: Future[HttpResponse] =
        http.singleRequest(HttpRequest(uri = url))

      responseFuture
        .onComplete {
          case Success(res) =>
            res.entity.dataBytes
              .runFold(ByteString(""))(_ ++ _)
              .map(body => body.utf8String) onComplete {
              case Success(r) =>
                println(
                  s"""
                     |page found: $url - $theSender
                     |theSender: $theSender
                     |""".stripMargin)
                theSender ! WebPageContent(url, r)

                currentUrl = None
                context.system.scheduler.scheduleOnce(100 milliseconds, self, ProcessNext)

              case Failure(ex) =>
                println(s"failed: $ex")

                context.system.scheduler.scheduleOnce(100 milliseconds, self, ProcessNext)
            }

          case Failure(error) =>
            sys.error(s"something wrong: $error")

            self.tell(Collect(url), theSender)
        }
    case Vector() =>
      ()
  }

  override def receive: Receive = {
    case Collect(newUrl) =>
      urls = urls :+ (newUrl, sender)
      count += 1
      log.info(
        s"""========
           |  name: ${self.path.name}
           | count: $count
           |newUrl: $newUrl
           |  urls: $urls
           |========
         """.stripMargin)
      self ! ProcessNext

    case ProcessNext if currentUrl.isEmpty =>
      processUrl

    case ProcessNext =>
      context.system.scheduler.scheduleOnce(100 milliseconds, self, ProcessNext)
  }
}

object WebPageCollector {
  sealed trait Command
  case class Collect(url: String) extends Command

  case object ProcessNext extends Command

  def props(http: HttpExt): Props =
    Props(new WebPageCollector(http))
}


class WordCounter extends Actor with ActorLogging {
  private var requester: Option[ActorRef] = None
  private var url: String = ""
  private var count: Int = 0
  private var results: Seq[Seq[(String, Int)]] = Vector.empty

  override def receive: Receive = {
    case WordCounter.Count(url, html) =>
      requester = Some(sender)
      this.url = url
      val content = Jsoup.parse(html).text()
      val wordsList = content.split("[\\s]+").grouped(30).toList
      count = wordsList.length
      wordsList.foreach { words =>
        val wordCountingWorker = context.actorOf(WordCountingWorker.props)
        wordCountingWorker ! WordCountingWorker.Count(words.toList)
      }
      context.become(receiveCounts)
  }

  def receiveCounts: Receive = {

    case WordCounter.CountedWords(wordsAndCounts) if count - 1 == 0 =>
    /* last one */
      count -= 1
//      val finalResult = results.reduce((xs1, xs2) => accumulate(xs1, xs2))
//      val finalResult = results.reduce(accumulate)
      val finalResult =
        results.headOption.fold[Seq[(String, Int)]](Vector.empty){ head =>
          results.tail.foldLeft(head)((acc, x) => accumulate(acc, x))
        }

      implicit val reversedInt = Ordering.Int.reverse
      requester.foreach(_ ! WebPageWordCounted(url, finalResult.sortBy(_._2)))
      requester = None
      url = ""
      count = 0
      results = Vector.empty
      context.become(receive)

    case WordCounter.CountedWords(wordsAndCounts) =>
      count -= 1
      results = results :+ wordsAndCounts
  }

  def accumulate(results: Seq[(String, Int)],
                 wordsAndCounts: Seq[(String, Int)]): Seq[(String, Int)] =
    (results ++ wordsAndCounts)
      .groupBy { case (word, count) => word }
      .map { case (word, xs) =>
        (word, xs.map { case (_, count) => count }.sum)
      }.toVector
}

object WordCounter {
  sealed trait Command
  case class Count(url: String, content: String) extends Command

  sealed trait Result
  case class CountedWords(wordAndCount: Seq[(String, Int)]) extends Result

  def props: Props = Props(new WordCounter)
}

class WordCountingWorker extends Actor with ActorLogging {
  override def receive: Receive = {
    case WordCountingWorker.Count(words) =>
      val wordAndCount = words.groupBy(identity)
                              .map { case (word, ws) => (word, ws.length) }
                              .toVector
                              .sortWith { case ((_, count1), (_, count2)) => count1 > count2 }
      sender ! WordCounter.CountedWords(wordAndCount)
  }
}

object WordCountingWorker {
  sealed trait Command

  case class Count(words: List[String]) extends Command

  def props: Props =  Props(new WordCountingWorker)
}