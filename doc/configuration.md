# Configuring a pcp-broker instance

Here we'll walk you through the required services and configuration
files needed to set up a pcp-broker listening on `wss://0.0.0.0:8142/pcp`

## Service dependencies

In order to use the pcp-broker you will need to bootstrap a number of
dependant trapperkeeper services - a Webserver service, a Webrouting
service, a Status service, and a Metrics service.

```
# bootstrap.cfg
puppetlabs.pcp.broker.service/broker-service
puppetlabs.trapperkeeper.services.authorization.authorization-service/authorization-service
puppetlabs.trapperkeeper.services.webrouting.webrouting-service/webrouting-service
puppetlabs.trapperkeeper.services.webserver.jetty9-service/jetty9-service
puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-service
puppetlabs.trapperkeeper.services.status.status-service/status-service
```

## Service configuration


The authorization subsystem will need to be configured following the notes on
mapping messages to ring requests in [authentication](authentication.md) and
the notes on how to [configure trapperkeeper-authorization](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md).

To disable all authorization you will need a null policy like so:

```
# authorization.conf
authorization: {
  version: 1
  rules: [
    {
      name: "no limits"
      match-request: {
        path: "^/"
        type: regex
      }
      sort-order: 1
      allow-unauthenticated: true
    }
  ]
}
```

The webserver needs to be configured for ssl against the puppet CA for
your install (see [authentication](authentication.md)), with
[client-auth](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md#client-auth)
set to `need` or `want`

```
# webserver.conf
webserver: {
    client-auth want
    ssl-port 8142
    ssl-host 0.0.0.0
    ssl-key /var/lib/puppet/ssl/private_keys/broker.example.com.pem
    ssl-cert /var/lib/puppet/ssl/certs/broker.example.com.pem
    ssl-ca-cert /var/lib/puppet/ssl/ca/ca_crt.pem
    ssl-crl-path /var/lib/puppet/ssl/ca/ca_crl.pem
}
```

The brokers protocol handlers and the status service will need to be mounted using a
[webrouting](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/webrouting-config.md)
configuration.

The v2 webroute is optional.

```
web-router-service: {
    "puppetlabs.trapperkeeper.services.status.status-service/status-service": "/status"
    "puppetlabs.pcp.broker.service/broker-service": {
       v1: "/pcp"
       v2: "/pcp2"
    }
}
```

The broker exposes several configuration options around controller and client
connections in the `pcp-broker` section. These options are:

* controller-uris: An array of Websocket URIs to which the broker will attempt
  to establish outbound connections.
* controller-whitelist: An array of message types the broker will accept from
  connected controllers. Defaults to accepting only inventory requests.
* controller-disconnection-graceperiod: The number of milliseconds after losing
  connectivity to all configured controllers that the broker will wait before
  dropping all connected clients (to allow them to redistribute to other
  brokers).
* max-connections: The maximum number of clients that can connect to the
  broker. Defaults to 0, which is interpreted as unlimited.

```
pcp-broker: {
    controller-uris: ["wss://broker.example.com:8143/server", "wss://broker2.example.com:8143/server"],
    controller-whitelist: ["http://puppetlabs.com/inventory_request",
                           "http://puppetlabs.com/rpc_blocking_request"],
    controller-disconnection-graceperiod: 45000
    max-connections: 10000
}
```
