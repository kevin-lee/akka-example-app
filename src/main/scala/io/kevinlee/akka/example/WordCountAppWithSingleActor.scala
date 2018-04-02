package io.kevinlee.akka.example

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.kevinlee.akka.example.WordCountMainActor.Count
import org.jsoup.Jsoup

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

/**
  * @author Kevin Lee
  * @since 2018-02-17
  */
object WordCountAppWithSingleActor extends App {
  val system = ActorSystem("wordCountApp")

  val wordCountMainActor = system.actorOf(SingleWordCountMainActor.props)
  wordCountMainActor ! Count(InputValues.websites)

}

class SingleWordCountMainActor extends Actor with ActorLogging {
  implicit val system = context.system
  var start = 0L

  implicit val executionContext = context.system.dispatcher

  implicit val materializer = ActorMaterializer()

  def count(url: String): (String, Seq[(String, Int)]) = {

      val http = Http()
      // access the web page and collect it
      val responseFuture: Future[HttpResponse] =
        http.singleRequest(HttpRequest(uri = url))

    Await.result(
      responseFuture
        .flatMap {
          res =>
            res.entity.dataBytes
              .runFold(ByteString(""))(_ ++ _)
              .map(body => body.utf8String)
              .map { html =>
                val content = Jsoup.parse(html).text()
                val wordsList = content.split("[\\s]+")
                val wordsAndCounts = wordsList.groupBy(identity)
                         .map { case (word, ws) => (word, ws.length) }
                         .toVector
                         .sortWith { case ((_, count1), (_, count2)) => count1 > count2 }

                (url, wordsAndCounts)

            }
        }, Duration.Inf)
  }

  override def receive: Receive = {
    case Count(urls) =>
      start = System.currentTimeMillis()
      val results = urls.map { url =>
        count(url)
      }
      val howLong = System.currentTimeMillis() - start
      println(
        s"""
           |results: ${results.mkString("\n================\n")}
           |It took $howLong ms.
         """.stripMargin)
  }

}

object SingleWordCountMainActor {
  sealed trait Command
  case class Count(urls: List[String]) extends Command

  sealed trait Result
  case class WebPageContent(url: String, content: String) extends Result
  case class WebPageWordCounted(url: String, wordsAndCount: Seq[(String, Int)]) extends Result

  def props: Props = Props(new SingleWordCountMainActor())
}
