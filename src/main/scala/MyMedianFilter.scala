import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props, _}
import akka.pattern.ask
import akka.util.Timeout
import javax.imageio.ImageIO

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Sorting

case class MyMedianFilter(img: BufferedImage, fileName: String)

case class parallelMedianFilter(img: BufferedImage, fileName: String)

class MyMedianActor extends Actor {

  // Noise Filtered image
  private var image: BufferedImage = _

  def receive: PartialFunction[Any, Unit] = {

    case MyMedianFilter(img, fileName) =>

      // Getting image dimensions to create new Buffered image with the same dimensions
      val width = img.getWidth()
      val height = img.getHeight()

      // Storing start time of median filter execution
      val startTime = System.currentTimeMillis()
      this.image = median(img, 1, 1, width, height)

      // Creates name of image file to be stored
      val output = new File(s"output/$fileName.png")

      // Respond to client
      sender() ! (this.image, fileName, output, deltaTime(startTime))

    case parallelMedianFilter(img, fileName) =>

      // Getting image dimensions to create new Buffered image with the same dimensions
      val width = img.getWidth()
      val height = img.getHeight()

      // Storing startTime of parallel median filter execution
      val startTime = System.currentTimeMillis()

      // Calculating median filter by splitting image in four parts, then processing split images in parallel
      val imageParts = Array((img, 1, 1, width / 2, height / 2), (img, width / 2 - 1, 1, width, height / 2),
        (img, 1, height / 2 - 1, width / 2, height), (img, width / 2 - 1, height / 2 - 1, width, height))
      imageParts.par.foreach(f => {
        this.image = median(f._1, f._2, f._3, f._4, f._5)
      })

      // Creates the filtered image and stores it in the output directory
      val output = new File(s"output/$fileName.png")

      // Respond to client
      sender() ! (this.image, fileName, output, deltaTime(startTime))

    case _ =>
      // Terminates actors
      context.system.terminate()
  }

  def currentTime: Long = System.currentTimeMillis()

  def deltaTime(t: Long): Long = currentTime - t

  def median(img: BufferedImage, xPos: Int, yPos: Int, width: Int, height: Int): BufferedImage = {

    val matrix = new Array[Int](9)

    for (i <- xPos until width - 1)
      for (j <- yPos until height - 1) {
        matrix(0) = img.getRGB(i - 1, j - 1)
        matrix(1) = img.getRGB(i - 1, j)
        matrix(2) = img.getRGB(i - 1, j + 1)
        matrix(3) = img.getRGB(i, j + 1)
        matrix(4) = img.getRGB(i + 1, j + 1)
        matrix(5) = img.getRGB(i + 1, j)
        matrix(6) = img.getRGB(i + 1, j - 1)
        matrix(7) = img.getRGB(i, j - 1)
        matrix(8) = img.getRGB(i, j)

        Sorting.quickSort(matrix)
        img.setRGB(i, j, matrix(4))
      }
    img
  }
}

object test extends App {

  val images = new java.io.File("img").listFiles
  val system = ActorSystem("MyMedianSystem")
  val myMedianActor = system.actorOf(Props[MyMedianActor], name = "myMedianActor")

  images.foreach(image => {
    var imageName = image.getName.replaceFirst("[.][^.]+$", "")
    var image_ = ImageIO.read(image)

    // Setting timeout for Future
    implicit val timeout: Timeout = Timeout(Duration(5, TimeUnit.MINUTES))

    // Sending image to be processed utilizing thee serial implementation
    val serialImage = myMedianActor ? MyMedianFilter(image_, s"series-$imageName")

    // Awaitng result of future
    val resultSerial = Await.result(serialImage, timeout.duration).asInstanceOf[(BufferedImage, String, File, String)]

    // Sending image to be processed utilizing thee parallel implementation
    val parallelImage = myMedianActor ? parallelMedianFilter(image_, s"parallel-$imageName")

    // Awaitng result of future
    val resultParallel = Await.result(parallelImage, timeout.duration).asInstanceOf[(BufferedImage, String, File, String)]

    // Outputs
    ImageIO.write(resultSerial._1, "png", resultSerial._3)
    println(s"Total execution time in series for ${resultSerial._2}: ${resultSerial._4}ms")
    ImageIO.write(resultParallel._1, "png", resultParallel._3)
    println(s"Total execution time in series for ${resultParallel._2}: ${resultParallel._4}ms")
  })

  // Terminate actors
  myMedianActor ! "Terminate actors"


  Await.ready(system.whenTerminated, Duration(5, TimeUnit.MINUTES))
}




