package com.phonebridge.shared
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.*
import javax.crypto.spec.*
class CryptoBox(private val context:Context,private val alias:String){
 fun ensureKeyPair():KeyPair = CompatibleKeyStore(context, alias).getOrCreate()
 fun publicKeyB64()=Base64.encodeToString(ensureKeyPair().public.encoded,Base64.NO_WRAP)
 private fun pub(s:String):java.security.interfaces.ECPublicKey {
  require(s.length in 1..512) { "Invalid peer key size" }
  val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(s,Base64.NO_WRAP))) as java.security.interfaces.ECPublicKey
  val params=AlgorithmParameters.getInstance("EC").apply { init(java.security.spec.ECGenParameterSpec("secp256r1")) }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
  require(key.params.curve == params.curve && key.params.generator == params.generator && key.params.order == params.order && key.params.cofactor == params.cofactor) { "Only P-256 peer keys are accepted" }
  return key
 }
 fun validatePeerKey(peer:String) { pub(peer) }
 fun deriveAesKey(peer:String,pairId:String):ByteArray{val k=ensureKeyPair();val a=KeyAgreement.getInstance("ECDH");a.init(k.private);a.doPhase(pub(peer),true);val shared=a.generateSecret();val salt=MessageDigest.getInstance("SHA-256").digest("PhoneBridge|$pairId".toByteArray());return hkdf(shared,salt,"PhoneBridge-AES-GCM-v1".toByteArray(),32)}
 fun encrypt(key:ByteArray,p:ByteArray,aad:ByteArray):Pair<String,String>{val n=ByteArray(12).also{SecureRandom().nextBytes(it)};val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,n));c.updateAAD(aad);return Base64.encodeToString(c.doFinal(p),Base64.NO_WRAP) to Base64.encodeToString(n,Base64.NO_WRAP)}
 fun decrypt(key:ByteArray,ct:String,n:String,aad:ByteArray):ByteArray{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,Base64.decode(n,Base64.NO_WRAP)));c.updateAAD(aad);return c.doFinal(Base64.decode(ct,Base64.NO_WRAP))}
 private fun hkdf(i:ByteArray,s:ByteArray,info:ByteArray,len:Int):ByteArray{val m=Mac.getInstance("HmacSHA256");m.init(SecretKeySpec(s,"HmacSHA256"));val prk=m.doFinal(i);val out=ByteArray(len);var prev=ByteArray(0);var off=0;var ctr=1;while(off<len){m.init(SecretKeySpec(prk,"HmacSHA256"));m.update(prev);m.update(info);m.update(ctr.toByte());prev=m.doFinal();val t=minOf(prev.size,len-off);prev.copyInto(out,off,0,t);off+=t;ctr++};return out}
}
