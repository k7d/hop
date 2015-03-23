# Hop

Hop is a minimalistic asynchronous web server for Clojure designed to be used in concert with [core.async](https://github.com/clojure/core.async).

Hop's request and response formats are compatible with [Ring](https://github.com/ring-clojure/ring/wiki/Concepts). Hop's request handlers however must return core.async channel and supply the response via the channel.

Under the hood, Hop is built on top of [Netty framework](http://netty.io/).


## Example

```clojure
(ns hello-world.core
  (:require [clojure.core.async :refer [go <! timeout]]
            [hop.server :as server]))

(defn handler [req]
  (go
    ; simulate wait (in real world this would be waiting for some I/O to happen)
    (<! (timeout 1000))
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body "Hello World"}))

(defn -main [& args]
  (server/start-server handler))
```
