package crypto

import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import sha256.{Hmac, Sha256}

import codecs.{ByteVector32, ByteVector64}

case class Hmac256(key: ByteVector) {
  def mac(message: ByteVector): ByteVector32 =
    Crypto.hmac256(key, message)

  def verify(mac: ByteVector32, message: ByteVector): Boolean =
    this.mac(message) === mac
}

object Crypto {
  def sha256(x: ByteVector): ByteVector32 =
    ByteVector32(
      ByteVector(
        Sha256.sha256(x.toArray.map[UByte](_.toUByte)).map[Byte](_.toByte)
      )
    )

  def hmac256(key: ByteVector, message: ByteVector): ByteVector32 =
    ByteVector32(
      ByteVector(
        Hmac
          .hmac(
            key.toArray.map[UByte](_.toUByte),
            message.toArray.map[UByte](_.toUByte)
          )
          .map[Byte](_.toByte)
      )
    )

  def getPublicKey(privateKey: ByteVector32): ByteVector =
    ByteVector(
      secp256k1
        .PrivateKey(privateKey.bytes.toArray.map[UByte](_.toUByte))
        .publicKey()
        .value
        .map[Byte](_.toByte)
    )

  def sign(data: ByteVector, privateKey: ByteVector32): ByteVector64 =
    ByteVector64(
      ByteVector(
        secp256k1
          .PrivateKey(privateKey.bytes.toArray.map[UByte](_.toUByte))
          .sign(data.toArray.map[UByte](_.toUByte))
          .toOption
          .get
          .map[Byte](_.toByte)
      )
    )

  def verifySignature(
      data: ByteVector,
      signature: ByteVector64,
      publicKey: ByteVector
  ): Boolean = {
    secp256k1
      .PublicKey(publicKey.toArray.map[UByte](_.toUByte))
      .verify(
        data.toArray.map[UByte](_.toUByte),
        signature.toArray.map[UByte](_.toUByte)
      )
      .toOption
      .getOrElse(false)
  }

  def multiplyPrivateKey(
      key: ByteVector32,
      scalar: ByteVector32
  ): ByteVector32 =
    ByteVector32(
      ByteVector(
        secp256k1
          .PrivateKey(key.bytes.toArray.map[UByte](_.toUByte))
          .multiply(scalar.bytes.toArray.map[UByte](_.toUByte))
          .value
          .map[Byte](_.toByte)
      )
    )

  def multiplyPublicKey(key: ByteVector, scalar: ByteVector32): ByteVector =
    ByteVector(
      secp256k1
        .PublicKey(key.toArray.map[UByte](_.toUByte))
        .multiply(scalar.bytes.toArray.map[UByte](_.toUByte))
        .value
        .map[Byte](_.toByte)
    )

  def G: ByteVector = ByteVector(
    secp256k1.Secp256k1.G.value.toArray.map[Byte](_.toByte)
  )
}
