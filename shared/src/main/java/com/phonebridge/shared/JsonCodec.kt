package com.phonebridge.shared
import org.json.JSONObject
object JsonCodec {
 fun encode(r: PlainRecord): String { val m=JSONObject(); r.metadata.forEach{(k,v)->m.put(k,v)}; return JSONObject().put("type",r.type).put("timestamp",r.timestamp).put("source",r.source).put("title",r.title).put("body",r.body).put("metadata",m).toString() }
 fun decode(s:String): PlainRecord { val o=JSONObject(s); val mo=o.optJSONObject("metadata")?:JSONObject(); val m=mutableMapOf<String,String>(); mo.keys().forEach{k->m[k]=mo.optString(k)}; return PlainRecord(o.getString("type"),o.getLong("timestamp"),o.optString("source"),o.optString("title"),o.optString("body"),m) }
}
