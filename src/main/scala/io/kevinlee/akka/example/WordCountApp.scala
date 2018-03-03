package io.kevinlee.akka.example

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.kevinlee.akka.example.WebPageCollector.Collect
import io.kevinlee.akka.example.WordCountMainActor.{Count, WebPageContent, WordCountResult}
import io.kevinlee.akka.example.WordCounter.CountedWords
import org.jsoup.Jsoup

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
  wordCountMainActor ! WordCountMainActor.Count(websites)

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

    case WebPageContent(url, html) =>
      // send html to WordCounter
//      println(s"WebPageContent is $html")
      val doc = Jsoup.parse(html)
      val content = doc.body().text()

      val wordCounter = context.actorOf(WordCounter.props())
      wordCounter ! WordCounter.Count(url, content)

    case WordCountResult(url, wordToCount) =>
      log.info(
        s"""
           |===========
           |wordToCount @ $url
           |-----------
           |  ${wordToCount.mkString(", ")}
         """.stripMargin)

  }
}

object WordCountMainActor {
  sealed trait Command
  case class Count(urls: List[String]) extends Command

  sealed trait Result
  case class WebPageContent(url: String, content: String) extends Result
  case class WordCountResult(url: String, wordToCount: Map[String, Int]) extends Result

  def props: Props = Props(new WordCountMainActor())
}

class WebPageCollector(http: HttpExt) extends Actor
                                         with ActorLogging {
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = context.system.dispatcher

  implicit val materializer = ActorMaterializer()

  override def receive: Receive = {
    case WebPageCollector.Collect(url) =>
      log.debug(s"$url - $sender")

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
              case Success(content) =>
                log.debug(s"$url - $theSender")
                theSender ! WebPageContent(url, content)

              case Failure(ex) =>
                log.debug(s"failed: $ex")
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

  private var sent = Map[String, ActorRef]()

  override def receive: Receive = {
    case WordCounter.Count(url, content) =>
      // extract words from the content
      // let WordCountingWorker count the words
      val wordCountingWorker = context.actorOf(WordCountingWorker.props)
      val words = content.split("[\\s]+").toList
      wordCountingWorker ! WordCountingWorker.Count(url, words)
      sent += url -> sender

    case CountedWords(url, wordAndCount) =>
      log.debug(s"I got $wordAndCount")
      sent.get(url) match {
        case Some(requester) =>
          requester ! WordCountResult(url, wordAndCount)
          sent -= url
        case None =>
          log.warning(s"There is not requester for the URL, $url")
      }
  }
}

object WordCounter {
  sealed trait Command
  case class Count(url: String, content: String) extends Command

  sealed trait Result
  case class CountedWords(url: String, wordAndCount: Map[String, Int])

  def props(): Props = Props(new WordCounter)
}

class WordCountingWorker extends Actor with ActorLogging {

//  1.
  private var map = Map[String, Int]()

//  2.
//  private val map = mutable.Map[String, Int]()

  override def receive: Receive = {
    case WordCountingWorker.Count(url, words) =>
      for (word <- words) {
        val count = map.get(word).fold(1)(_ + 1)
        map += word -> count
      }
      sender ! CountedWords(url, map)
      map = Map[String, Int]()

    case whatever =>
      log.error(s"Unknown command! $whatever")
  }
}

object WordCountingWorker {
  sealed trait Command

  case class Count(url: String, words: List[String]) extends Command

  def props(): Props = Props(new WordCountingWorker)
}