# cthun

A message broker implementing the cthun wire protocol

## Usage

First, run:

`lein tk`

## Message flow

Websocket -> Accept Queue -> Address Expansion -> Delivery Threadpool ->
Websocket (or Redelivery Queue)

Redelivery Queue -> Websocket (or Redelivery Queue)


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
