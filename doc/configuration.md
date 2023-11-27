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
puppetlabs.trapperkeeper.services.webserver.jetty10-service/jetty10-service
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
[client-auth](https://github.com/puppetlabs/trapperkeeper-webserver-jetty10/blob/master/doc/jetty-config.md#client-auth)
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
[webrouting](https://github.com/puppetlabs/trapperkeeper-webserver-jetty10/blob/master/doc/webrouting-config.md)
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
* controller-allowlist: An array of message types the broker will accept from
  connected controllers. Defaults to accepting  inventory requests, blocking and non blocking rpc requests.
* controller-disconnection-graceperiod: The number of milliseconds after losing
  connectivity to all configured controllers that the broker will wait before
  dropping all connected clients (to allow them to redistribute to other
  brokers). Defaults to 90s.
* max-connections: The maximum number of clients that can connect to the
  broker. Defaults to 0, which is interpreted as unlimited.
* max-message-size: The maximum message size, specified in bytes. Defaults to 64 MB. This value can be overriden and reduced to something smaller as desired.
* idle-timeout: The time, in milliseconds, to wait for an idle connection before closing it. The default is 360,000 (6 minutes) which is 3 times the client's default ping interval. Changing this setting should be coordinated with changes to the client's ping interval.
* crl-check-period: The time, in milliseconds, that pcp-broker will check to see if any connections in its inventory have been expired (currently, connections may be expired due to a CRL update). Default is 60,000 (1 minute).
* expired-conn-throttle: The time, in milliseconds, to wait between closing each expired connection. Default is 30. For example, a broker with 1,000 expired connections closing these connections will be spread out across at least 30 seconds.
```
pcp-broker: {
    controller-uris: ["wss://broker.example.com:8143/server", "wss://broker2.example.com:8143/server"],
    controller-allowlist: ["http://puppetlabs.com/inventory_request",
                           "http://puppetlabs.com/rpc_blocking_request"],
    controller-disconnection-graceperiod: "90s"
    max-connections: 10000
    idle-timeout: 360000
    crl-check-period: 60000
    expired-conn-throttle: 30
}
```
