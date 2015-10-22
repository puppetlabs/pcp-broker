# Logging in pcp-broker

We use the puppetlabs/structured-logging to log certain events as they
are handled by the broker.

## Types of messages

In order to allow you to more readily search through your structured
logs we add a :type field to each log entry that we generate.  These
types are as follows:


### `broker-init`

The broker is being initalised


### `broker-start`

The broker is being started


### `broker-started`

The broker has been started


### `broker-unhandled-message`

The broker recieved a message targetted at it that it cannot handle.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `messagetype` String - The message_type of the message


### `broker-state-transition-unknown`

The broker couldn't find a handler function for the named state.

* `state` String - the state


### `broker-stop`

The broker is stopping


### `broker-stopped`

The broker has been stopped


### `connection-open`

A client has connected to the broker.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)


### `connection-message`

Received a message from a client

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)


### `connection-already-associated`

A client that is associated attempted to associate again.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `uri` String - PCP uri of the new association
* `existinguri` String - PCP uri of the existing association


### `connection-association-failed`

A client failed to become associated with the broker

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `uri` String - PCP uri of the association


### `connection-message-before-association`

A client sent a message before it was associated with the broker

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)


### `connection-error`

A websocket session error on a connection.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)


### `connection-close`

A client has disconnected from the broker.

* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)
* `statuscode` Numeric - the websockets status code
* `reason`  String - the reason given for disconnection


### `message-authorization`

A message has been checked if it can be relayed by the broker.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `allowed` Boolean - was the message allowed
* `message` String - why the message was allowed/denied


### `message-expired`

A message has hit its expiry (discovered when sending).

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)


### `message-expired-from-server`

A message that hit its expiry was sourced from the server.


### `message-delivery-failure`

A message delivery failed.   This may not be fatal, the message may be retried later.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `reason` String - description of why the delivery failed


### `message-redelivery`

A message has been scheduled for redelivery.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `delay` Number - how far in the future will we retry delivery


### `message-delivery`

A message is being delivered to a client.

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* [`commonname`](#commonname)
* [`remoteaddress`](#remoteaddress)

### `message-deliver-error`

An exception was raised during message delivery.


### `queue-enqueue`

A message is being spooled to an internal queue

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `queue`  String - name of the queue


### `queue-dequeue`

A message is being consumed from an internal queue

* [`messageid`](#messageid)
* [`sender`](#sender)
* [`destination`](#destination)
* `queue`  String - name of the queue


### `queue-dequeue-error`

A failure happened in consuming/processing a message from an internal queue

* `queue`  String - name of the queue


# Common data

The pcp-broker manages connections and messages, and so log data will
often include there common properties about these primitives.

## Connections

When reporting about connections we provide the following pieces of
information:

### `commonname`

The Common Name from the x509 Client certificate

### `remoteaddress`

The host:port of the remote end of the connection

## Messages

When reporting about messages we provide the following pieces of
information:

### `messageid`

The string uuid of a message

### `source`

The sender of the message as a PCP Uri

### `destination`

An array or single PCP Uri
