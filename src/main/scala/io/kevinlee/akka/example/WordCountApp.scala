package io.kevinlee.akka.example

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.kevinlee.akka.example.WebPageCollector.Collect
import io.kevinlee.akka.example.WordCountMainActor.{Count, WebPageContent}

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

    case WebPageContent(content) =>
      // send content to WordCounter
      println(s"WebPageContent is $content")
  }
}

object WordCountMainActor {
  sealed trait Command
  case class Count(urls: List[String]) extends Command

  sealed trait Result
  case class WebPageContent(content: String) extends Result

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
                theSender ! WebPageContent(r)

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
  override def receive: Receive = {
    case Count(content) =>
      // extract words from the content
      // let WordCountingWorker count the words
      ???
  }
}

object WordCounter {
  sealed trait Command
  case class Count(content: String) extends Command

  sealed trait Result
  case class CountedWords(wordAndCount: List[(String, Int)])
}

class WordCountingWorker extends Actor with ActorLogging {
  override def receive: Receive = {
    case Count(words) =>
      ???
  }
}

object WordCountingWorker {
  sealed trait Command

  case class Count(words: List[String]) extends Command
}