import io.netty.handler.ssl.SslContextBuilder
import zhttp.http.{Header, HttpData}
import zhttp.service.client.ClientSSLHandler.SslClientOptions
import zhttp.service.{ChannelFactory, Client, EventLoopGroup}
import zio._
import java.io.{ InputStream}
import java.security.KeyStore

import javax.net.ssl.TrustManagerFactory

object HttpsClient extends App {
  val env     = ChannelFactory.auto ++ EventLoopGroup.auto()
  val url     = "https://sports.api.decathlon.com/groups/water-aerobics"
  val headers = List(Header.host("sports.api.decathlon.com"))

  //Configuring Truststore for https(optional)
  val trustStore: KeyStore                     = KeyStore.getInstance("JKS")
  val trustStorePassword                       = "changeit"
  val trustStoreFile: InputStream              = getClass.getResourceAsStream("truststore.jks")
  val trustManagerFactory: TrustManagerFactory =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)

  trustStore.load(trustStoreFile, trustStorePassword.toCharArray)
  trustManagerFactory.init(trustStore)

  val sslOption: SslClientOptions =
    SslClientOptions.CustomSslClient(SslContextBuilder.forClient().trustManager(trustManagerFactory).build())

  val program = for {
    res <- Client.request(url, headers, sslOption)
    _   <- console.putStrLn {
      res.content match {
        case HttpData.CompleteData(data) => data.map(_.toChar).mkString
        case HttpData.StreamData(_)      => "<Chunked>"
        case HttpData.Empty              => ""
      }
    }
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = program.exitCode.provideCustomLayer(env)

}
