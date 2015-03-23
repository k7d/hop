# Hop

Hop is a minimalistic asynchronous web server for Clojure designed to be used in concert with [core.async](https://github.com/clojure/core.async).

Hop's request and response formats are compatible with [Ring](https://github.com/ring-clojure/ring/wiki/Concepts). Hop's request handlers however must return core.async channel and supply the response via the channel.

Under the hood, Hop is built on top of Netty framework.


## Example

```clojure
(ns `hello-world.core
	(:require [clojure.core.async :refer [go <! timeout]]))

(defn handler [request]

  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "Hello World"})
```   
