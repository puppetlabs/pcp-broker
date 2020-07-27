require 'fileutils'
require 'openssl'

module PuppetSpec
  module SSL

    PRIVATE_KEY_LENGTH = 2048
    FIVE_YEARS = 5 * 365 * 24 * 60 * 60
    CA_EXTENSIONS = [
      ["basicConstraints", "CA:TRUE", true],
      ["keyUsage", "keyCertSign, cRLSign", true],
      ["subjectKeyIdentifier", "hash", false],
      ["authorityKeyIdentifier", "keyid:always", false]
    ]
    NODE_EXTENSIONS = [
      ["keyUsage", "digitalSignature,keyEncipherment", true],
      ["subjectKeyIdentifier", "hash", false]
    ]
    DEFAULT_SIGNING_DIGEST = OpenSSL::Digest::SHA256.new
    DEFAULT_REVOCATION_REASON = OpenSSL::OCSP::REVOKED_STATUS_KEYCOMPROMISE
    DIGEST = OpenSSL::Digest::SHA256.new

    def self.create_private_key(length = PRIVATE_KEY_LENGTH)
      OpenSSL::PKey::RSA.new(length)
    end

    def self.self_signed_ca(key, name)
      cert = OpenSSL::X509::Certificate.new

      cert.public_key = key.public_key
      cert.subject = OpenSSL::X509::Name.parse("/CN=#{name}")
      cert.issuer = cert.subject
      cert.version = 2
      cert.serial = rand(2**128)

      not_before = just_now
      cert.not_before = not_before
      cert.not_after = not_before + FIVE_YEARS

      ext_factory = extension_factory_for(cert, cert)
      CA_EXTENSIONS.each do |ext|
        extension = ext_factory.create_extension(*ext)
        cert.add_extension(extension)
      end

      alt_names = [name, 'localhost', 'puppet'].map {|n| "DNS: #{n}" }.join(', ')
      extension = ext_factory.create_extension('subjectAltName', alt_names, false)
      cert.add_extension(extension)

      cert.sign(key, DEFAULT_SIGNING_DIGEST)

      cert
    end

    def self.create_csr(key, name)
      csr = OpenSSL::X509::Request.new

      csr.public_key = key.public_key
      csr.subject = OpenSSL::X509::Name.parse(name)
      csr.version = 2
      csr.sign(key, DEFAULT_SIGNING_DIGEST)

      csr
    end

    def self.sign(ca_key, ca_cert, csr, extensions = NODE_EXTENSIONS)
      cert = OpenSSL::X509::Certificate.new

      cert.public_key = csr.public_key
      cert.subject = csr.subject
      cert.issuer = ca_cert.subject
      cert.version = 2
      cert.serial = rand(2**128)

      not_before = just_now
      cert.not_before = not_before
      cert.not_after = not_before + FIVE_YEARS

      ext_factory = extension_factory_for(ca_cert, cert)
      extensions.each do |ext|
        extension = ext_factory.create_extension(*ext)
        cert.add_extension(extension)
      end

      alt_names = ['localhost', 'puppet'].map {|n| "DNS: #{n}" }.join(', ')
      extension = ext_factory.create_extension('subjectAltName', alt_names, false)
      cert.add_extension(extension)

      cert.sign(ca_key, DEFAULT_SIGNING_DIGEST)

      cert
    end

    def self.create_crl_for(cert, key)
      crl = OpenSSL::X509::CRL.new
      crl.version = 1
      crl.issuer = cert.subject

      ef = extension_factory_for(cert)
      crl.add_extension(
        ef.create_extension(["authorityKeyIdentifier", "keyid:always", false]))
      crl.add_extension(
        OpenSSL::X509::Extension.new("crlNumber", OpenSSL::ASN1::Integer(0)))

      crl.last_update = just_now
      crl.next_update = just_now + FIVE_YEARS
      crl.sign(key, DEFAULT_SIGNING_DIGEST)

      crl
    end


   private

    def self.just_now
      Time.now - 1
    end

    def self.extension_factory_for(ca, cert = nil)
      ef = OpenSSL::X509::ExtensionFactory.new
      ef.issuer_certificate  = ca
      ef.subject_certificate = cert if cert

      ef
    end

    def self.bundle(*items)
      items.map {|i| EXPLANATORY_TEXT + i.to_pem }.join("\n")
    end
  end
end

NODES = %w{ client01 client02 client03 client03 client04 client05 controller01 controller02 broker }
DOMAIN = 'example.com'
ca_key = PuppetSpec::SSL.create_private_key
ca_pub = ca_key.public_key
ca_cert = PuppetSpec::SSL.self_signed_ca(ca_key, "Puppet CA: ca.#{DOMAIN}")

node_info = NODES.map do |name|
  key = PuppetSpec::SSL.create_private_key
  csr = PuppetSpec::SSL.create_csr(key, "/CN=#{name}.#{DOMAIN}")
  cert = PuppetSpec::SSL.sign(ca_key, ca_cert, csr)

  [name + '.' + DOMAIN, [key, key.public_key, cert]]
end

base = 'newcerts/'
certdir = base + 'certs/'
pubdir =  base + 'public_keys/'
privdir = base + 'private_keys/'
cadir =   base + 'ca/'

FileUtils.mkdir base
FileUtils.mkdir certdir
FileUtils.mkdir pubdir
FileUtils.mkdir privdir
FileUtils.mkdir cadir

node_info << ['ca', [ca_key, ca_key.public_key, ca_cert]]

node_info.each do |stuff|
  name, info = stuff
  File.write(privdir + name + '.pem', info[0])
  File.write(pubdir + name + '.pem',  info[1])
  File.write(certdir + name + '.pem', info[2])
end

crl = PuppetSpec::SSL.create_crl_for(ca_cert, ca_key)

File.write(cadir + 'ca_crt.pem', ca_cert.to_s)
File.write(cadir + 'ca_crl.pem', crl.to_s)

broker_info = node_info.find {|i| i[0] == 'broker.' + DOMAIN }
broker_cert = broker_info[1][2]

File.write(certdir + 'broker-chain.' + DOMAIN + '.pem', broker_cert.to_s + "\n" + ca_cert.to_s)

