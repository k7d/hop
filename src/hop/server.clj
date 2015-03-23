(ns hop.server
  (:require [clojure.tools.logging :as log])
  (:import (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.bootstrap ServerBootstrap)
           (io.netty.channel ChannelOption
                             ChannelInitializer
                             ChannelHandlerContext
                             ChannelFutureListener
                             ChannelInboundHandlerAdapter)
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
                                        HttpHeaderValues)
           (io.netty.buffer Unpooled)
           (java.io Closeable))
  (:gen-class))

(defn request-handler []
  (proxy [ChannelInboundHandlerAdapter] []
    (channelReadComplete [^ChannelHandlerContext ctx]
      (.flush ctx))

    (channelRead [^ChannelHandlerContext ctx msg]
      (when (instance? HttpRequest msg)
        (when (HttpHeaderUtil/is100ContinueExpected msg)
          (.write ctx (DefaultFullHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/CONTINUE)))
        (let [response (DefaultFullHttpResponse.
                         HttpVersion/HTTP_1_1
                         HttpResponseStatus/OK
                         (Unpooled/wrappedBuffer (.getBytes "HELLO WORLD")))]
          (doto (.headers response)
            (.set HttpHeaderNames/CONTENT_TYPE "text/plain")
            (.setInt HttpHeaderNames/CONTENT_LENGTH (.readableBytes (.content response))))
          (if (HttpHeaderUtil/isKeepAlive msg)
            (do
              (.set (.headers response) HttpHeaderNames/CONNECTION HttpHeaderValues/KEEP_ALIVE)
              (.write ctx response))
            (-> ctx
                (.write response)
                (.addListener ChannelFutureListener/CLOSE))))))

    (exceptionCaught [^ChannelHandlerContext ctx ^Throwable cause]
      (.printStackTrace cause)
      (.close ctx))))

(defn initializer []
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel ch]
      (-> (.pipeline ch)
          (.addLast "http-server" (HttpServerCodec.))
          (.addLast "request-handler" (request-handler))))))

(defn start-server []
  (let [boss-group (NioEventLoopGroup. 1)
        worker-group (NioEventLoopGroup.)]
    (try
      (-> (ServerBootstrap.)
          (.option ChannelOption/SO_BACKLOG (Integer. 1024))
          (.group boss-group worker-group)
          (.channel NioServerSocketChannel)
          (.handler (LoggingHandler. LogLevel/INFO))
          (.childHandler (initializer))
          (.bind 8080)
          (.sync)
          (.channel)
          (.closeFuture)
          (.sync))
      (finally
        (.shutdownGracefully boss-group)
        (.shutdownGracefully worker-group)))))

(defn -main [& args]
  (start-server))