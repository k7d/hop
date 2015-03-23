(ns hop.server
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.bootstrap ServerBootstrap)
           (io.netty.channel ChannelOption
                             ChannelInitializer
                             ChannelHandlerContext
                             ChannelFutureListener
                             ChannelInboundHandlerAdapter Channel)
           (io.netty.channel.socket.nio NioServerSocketChannel)
           (io.netty.handler.logging LoggingHandler
                                     LogLevel)
           (io.netty.channel.socket SocketChannel)
           (io.netty.handler.codec.http HttpServerCodec
                                        HttpRequest
                                        HttpHeaderUtil
                                        DefaultFullHttpResponse
                                        HttpVersion
                                        HttpResponseStatus
                                        HttpHeaderNames
                                        HttpHeaderValues HttpResponseEncoder HttpRequestDecoder HttpObjectAggregator FullHttpRequest)
           (io.netty.buffer Unpooled ByteBuf ByteBufInputStream)
           (java.io Closeable))
  (:gen-class))

(def question-marg)

(defn netty-request->ring-request [^FullHttpRequest req
                                   ^Channel ch]
  (let [question-mark-index (-> req .getUri (.indexOf (int \?)))]
    {:keep-alive?    (HttpHeaderUtil/isKeepAlive req)
     :request-method (-> req .getMethod .name str/lower-case keyword)
     :headers        (->> req .headers (into {}))
     :uri            (let [idx (long question-mark-index)]
                       (if (neg? idx)
                         (.getUri req)
                         (.substring (.getUri req) 0 idx)))
     :query-string   (let [idx (long question-mark-index)
                           uri (.getUri req)]
                       (if (neg? idx)
                         nil
                         (.substring uri (unchecked-inc idx))))
     :server-name    (some-> ch (.localAddress) (.getHostName))
     :server-port    (some-> ch (.localAddress) (.getPort))
     :remote-addr    (some-> ch (.remoteAddress) (.getAddress) (.getHostAddress))
     :body           (-> req (.content) (ByteBufInputStream.))}))


(defn ring-body->netty-content [body]
  ; TODO - based on Ring specs also need to handle ISeq, File and InputStream
  ; https://github.com/ring-clojure/ring/wiki/Concepts#responses
  (-> body
      (.getBytes)
      (Unpooled/wrappedBuffer)))

(defn ring-response->netty-response [res]
  (let [content (ring-body->netty-content (:body res))
        nres (DefaultFullHttpResponse.
               HttpVersion/HTTP_1_1
               HttpResponseStatus/OK
               content)
        headers (.headers nres)]
    (doseq [[name value] (:headers res)]
      (.set headers name value))
    (.setInt headers HttpHeaderNames/CONTENT_LENGTH (.readableBytes content))
    nres))

(defn netty-request-handler [ring-request-handler]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelReadComplete [^ChannelHandlerContext ctx]
      (.flush ctx))

    (channelRead [^ChannelHandlerContext ctx msg]
      (when (instance? FullHttpRequest msg)
        (when (HttpHeaderUtil/is100ContinueExpected msg)
          (.write ctx (DefaultFullHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/CONTINUE)))
        (let [response (-> (netty-request->ring-request msg (.channel ctx))
                           (ring-request-handler)
                           (ring-response->netty-response))]
          (if (HttpHeaderUtil/isKeepAlive msg)
            (do
              (.set (.headers response) HttpHeaderNames/CONNECTION HttpHeaderValues/KEEP_ALIVE)
              (.write ctx response))
            (-> ctx
                (.write response)
                (.addListener ChannelFutureListener/CLOSE))))))

    (exceptionCaught [^ChannelHandlerContext ctx ^Throwable cause]
      (log/error cause "error handling request")
      (.close ctx))))

(defn channel-initializer [request-handler]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      (-> (.pipeline ch)
          (.addLast "http-request-enc" (HttpResponseEncoder.))
          (.addLast "http-request-dec" (HttpRequestDecoder.))
          (.addLast "http-request-aggr" (HttpObjectAggregator. 8388608))  ; 8MB
          (.addLast "request-handler" (netty-request-handler request-handler))))))

(defn server-bootstrap [event-loop-group channel-class request-handler]
  (-> (ServerBootstrap.)
      (.option ChannelOption/SO_BACKLOG (int 1024))
      (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
      (.group event-loop-group)
      (.channel channel-class)
      (.handler (LoggingHandler. LogLevel/INFO))
      (.childHandler (channel-initializer request-handler))
      (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)))

(defprotocol HopServer
  (stop-server [_] "Stutdowns the server."))

(defn start-server [handler]
  (let [event-loop-group (NioEventLoopGroup.)
        channel-class NioServerSocketChannel
        channel (-> (server-bootstrap event-loop-group channel-class handler)
                    (.bind 8080)
                    (.sync)
                    (.channel))]
    (reify HopServer
      (stop-server [_]
        (-> channel (.close) (.sync))
        (-> event-loop-group (.shutdownGracefully))))))

(defn hello-world-handler [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "Hello World\n"})

(defn -main [& args]
  (start-server hello-world-handler))