(ns puppetlabs.cthun.meshing)

(defprotocol MeshingService
  (record-client-location [this client])
  (forget-client-location [this client])
  (deliver-to-client [this client message])
  (register-local-delivery [this fn]))
