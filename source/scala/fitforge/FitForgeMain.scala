package fitforge

import java.time.Instant

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http

import fitforge.fit.GarminFitCodec
import fitforge.http.FitForgeRoutes
import fitforge.store.S3FitStore

/** Entry point: bind the HTTP server over an S3-backed store and schedule the TTL sweeper. */
object FitForgeMain {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("fit-forge")
    import system.dispatcher
    val log = system.log

    val config = FitForgeConfig.fromEnv()
    val store  = new S3FitStore(config.s3Bucket, S3FitStore.s3Settings(config.awsRegion), () => Instant.now())

    val routes = new FitForgeRoutes(store, new GarminFitCodec(), config).routes

    Http().newServerAt("0.0.0.0", config.port).bind(routes).onComplete {
      case Success(_)  => log.info("fit-forge online at http://0.0.0.0:{}/ (bucket {})", config.port, config.s3Bucket)
      case Failure(ex) => log.error("Failed to bind HTTP server: {}", ex.getMessage)
    }

    val sweeper = new Runnable {
      def run(): Unit = { val _ = store.sweepExpired() }
    }
    val _ = system.scheduler.scheduleAtFixedRate(5.minutes, 5.minutes)(sweeper)

    val _ = sys.addShutdownHook {
      system.terminate()
      Await.result(system.whenTerminated, 30.seconds): Unit
    }
  }
}
