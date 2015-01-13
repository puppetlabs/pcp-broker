# cthun

A trapperkeeper web app designed to ... well, that part is up to you.

## Usage

First, run:

`lein tk`

Then, open a browser and visit:

http://localhost:8080/hello/cthun


## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


## Message flow

Websocket -> QueueingService (persists to reliable storage) ->
InventoryService (expands destinations) -> Websocket


### Choosing queuing

In bootstrap.cfg you will need one of the following queueing-services

    puppetlabs.cthun.queueing.durable-queue/queueing-service
    puppetlabs.cthun.queueing.activemq/queueing-service

### Choosing inventory

In bootstrap.cfg you will need one of the following inventory-services
(it's imagined puppetdb may be an inventory service in future)

The `in-memory` inventory service does not persist any storage to disk.

    puppetlabs.cthun.inventory.in-memory/inventory-service

### Choosing meshing

In booststrap.cfg (you cannot put comments in bootstrap.cfg) you will
need one of the following meshing-services

The `in-memory` meshing service does no meshing.

    puppetlabs.cthun.meshing.in-memory/meshing-service
    puppetlabs.cthun.meshing.hazelcast/meshing-service
