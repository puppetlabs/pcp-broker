# Message authorization

Messages can be allowed or denied using the trapperkeeper-authorization subsystem.

https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md

## Message mapping

PCP Message are mapped into ring requests, on the `/pcp-broker/send` path.

As a worked example, the envelope of a message used by the ping
application would look something like this:


    {:id "3790c4a2-dd71-41bf-bd6d-573779b38657"
     :sender "pcp://client01.example.com/ruby-pcp-client-2251"
     :targets [ "pcp://client02.example.com/agent" ]
     :message_type "http://puppetlabs.com/rpc_blocking_request"}

This would be transformed into the following ring request:

    {:request-method :post
     :remote-addr "192.168.1.22:36362"
     :uri "/pcp-broker/send"
     :ssl-client-cert (X509-certificate-for "client01.example.com")
     :form-params {}
     :query-params {"message_type" "http://puppetlabs.com/rpc_blocking_request"
                    "sender" "pcp://client01.example.com/ruby-pcp-client-2251"
                    "targets" "pcp://client02.example.com/agent"
                    "destination_report" false}
     :params {"message_type" "http://puppetlabs.com/rpc_blocking_request"
              "sender" "pcp://client01.example.com/ruby-pcp-client-2251"
              "targets" "pcp://client02.example.com/agent"
              "destination_report" false}}

And then this can be matched by trapperkeeper-authorization with the following `authorization.conf`.

``` HOCON
# authorization.conf
authorization: {
  version: 1
  rules: [
    {
      name: "pxp command message"
      match-request: {
        type: path
        path: "/pcp-broker/send"
        query-params: {
          message_type: [
            "http://puppetlabs.com/rpc_blocking_request"
          ]
        }
      }
      allow: [
        client01.example.com
      ]
      sort-order: 400
    },
    {
      name: "pcp message"
      match-request: {
        type: path
        path: "/pcp-broker/send"
      }
      allow-unauthenticated: true
      sort-order: 420
    },
  ]
}
```

For further notes on how to configure trapperkeeper-authorization see
https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md

## Common Patterns

### Session Association

Rejecting session association is one way of blocking nodes that have previously acquired a valid SSL
certificate - or have those certificates for other purposes - from participating in PCP activity.

Session association requests can be matched by trapperkeeper-authorization with the following
`authorization.conf`.

``` HOCON
# authorization.conf
authorization: {
  version: 1
  rules: [
    {
      name: "deny pcp association"
      match-request: {
        type: path
        path: "/pcp-broker/send"
        query-params: {
          message_type: [
            "http://puppetlabs.com/associate_request"
          ]
        }
      }
      deny: [
        client02.example.com
      ]
      sort-order: 400
    },
    {
      name: "pcp message"
      match-request: {
        type: path
        path: "/pcp-broker/send"
      }
      allow-unauthenticated: true
      sort-order: 420
    },

  ]
}
```

### Inventory Request

Not all nodes need or should have access to the full inventory of nodes connected to a PCP broker.
Inventory requests are one way of acquiring that information; another functionally equivalent way
to discover all connected nodes is a message using a wildcard to specify the target that requests
a destination report.

Both types of requests can be matched by trapperkeeper-authorization with the following
`authorization.conf`.

``` HOCON
# authorization.conf
authorization: {
  version: 1
  rules: [
    {
      name: "restrict pcp inventory"
      match-request: {
        type: path
        path: "/pcp-broker/send"
        query-params: {
          message_type: [
            "http://puppetlabs.com/inventory_request"
          ]
        }
      }
      allow: [
        controller01.example.com
      ]
      sort-order: 400
    },
    {
      names: "restrict pcp multi-cast destination_report"
      match-request: {
        type: path
        path: "/pcp-broker/send"
        query-params: {
          targets: [
            "pcp://*/agent",
            "pcp://*/*",
          ]
          destination_report: true
        }
      }
      allow: [
        controller01.example.com
      ]
      sort-order: 400
    },
    {
      name: "pcp message"
      match-request: {
        type: path
        path: "/pcp-broker/send"
      }
      allow-unauthenticated: true
      sort-order: 420
    },

  ]
}
```

