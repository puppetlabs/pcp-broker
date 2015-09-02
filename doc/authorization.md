# Message authorization

Messages can be allowed or denied following a simple table-based model
of configuration.

Message processing starts at the `accept` table, and applies all
`rules` from that table until a terminal state is reached.

## Table

A table has a `default` state (`allow` or `deny`) which applies when
no rules in that table match, and an optional list of `rules` rules
which are applied in order.

## Rule

A rule specifies a `message_type` or `sender` that the rule matches,
and an `action` which may be `allow`, `deny`, or a map indicating a
table to jump to.

# Default ruleset

The default ruleset when specified is as follows:

```
pcp-broker: {
    authorization: {
        accept: {
            default: allow
        }
    }
}
```

# Worked example

Given a PXP-like system we can imagine we have two request types
`pxp_request` and `pxp_status` that we want to group and then allow
access to only if the sender matches `pxp-controller`.

```
pcp-broker: {
    authorization: {
        accept: {
            default: allow
            rules: [
                {
                    message_type: "pxp_request"
                    action: {
                        target: pxp_commands
                    }
                }
                {
                    message_type: "pxp_status"
                    action: {
                        target: pxp_commands
                    }
                }
            ]
        }
        pxp_commands: {
            default: deny
            rules: [
                {
                    sender: pxp-controller
                    action: allow
                }
            ]
        }
    }
}
```
