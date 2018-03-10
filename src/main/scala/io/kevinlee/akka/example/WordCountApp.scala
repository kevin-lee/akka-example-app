package io.kevinlee.akka.example

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.kevinlee.akka.example.WebPageCollector.Collect
import io.kevinlee.akka.example.WordCountMainActor.{Count, WebPageContent, WebPageWordCounted}

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * @author Kevin Lee
  * @since 2018-02-17
  */
object WordCountApp extends App {
  val system = ActorSystem("wordCountApp")

  val wordCountMainActor = system.actorOf(WordCountMainActor.props)
  private val websites =
    List(
      "https://www.google.com.au/search?q=akka",
      "https://www.google.com.au/search?q=scala",
      "https://www.google.com.au/search?q=play+framework"
    )
  wordCountMainActor ! Count(websites)

}

class WordCountMainActor extends Actor with ActorLogging {
  implicit val system = context.system

  override def receive: Receive = {
    case Count(urls) =>
      urls.foreach { url =>
        // create WebPageCollector. It is not WebPageCollector type but ActorRef type.
        val webPageCollector = context.actorOf(WebPageCollector.props(Http()))
        webPageCollector ! Collect(url)
      }

    case WebPageContent(url, content) =>
      // send content to WordCounter
      val wordCounter = context.actorOf(WordCounter.props)
      wordCounter ! WordCounter.Count(url, content)

    case WebPageWordCounted(url, wordsAndCounts) =>
      println(
        s"""
           |           url: $url
           |wordsAndCounts: $wordsAndCounts
           |""".stripMargin)
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

  override def receive: Receive = {
    case Collect(url) =>
      println(s"$url - $sender")

      val theSender = sender

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
                println(s"$url - $theSender")
                theSender ! WebPageContent(url, r)

              case Failure(ex) =>
                println(s"failed: $ex")
            }

          case Failure(_)   =>
            sys.error("something wrong")
        }
  }
}

object WebPageCollector {
  sealed trait Command
  case class Collect(url: String) extends Command

  def props(http: HttpExt): Props =
    Props(new WebPageCollector(http))
}


class WordCounter extends Actor with ActorLogging {
  private var requester: Option[ActorRef] = None
  private var url: String = ""
  private var count: Int = 0
//  private var results: Seq[(String, Int)] = Vector.empty
  private var results: Seq[Seq[(String, Int)]] = Vector.empty

  override def receive: Receive = {
    case WordCounter.Count(url, content) =>
      requester = Some(sender)
      this.url = url
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

      requester.foreach(_ ! WebPageWordCounted(url, finalResult))
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
      sender ! WordCounter.CountedWords(wordAndCount)
  }
}

object WordCountingWorker {
  sealed trait Command

  case class Count(words: List[String]) extends Command

  def props: Props =  Props(new WordCountingWorker)
}