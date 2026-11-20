#!/usr/bin/env python3
"""Run the real pin recovery and scheduling code against Java fakes; no APK or Android runtime.

Needs javac + java, or java and WG_ECJ_JAR pointing to Eclipse's batch compiler.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
'android/text/TextUtils.java': r'''package android.text;
public class TextUtils {
  public static boolean isEmpty(CharSequence s) {return s==null || s.length()==0;}
  public static boolean equals(CharSequence a, CharSequence b) {return a==null ? b==null : a.toString().contentEquals(b==null ? "" : b);}
}''',
'android/content/SharedPreferences.java': r'''package android.content;
import java.util.*;
public class SharedPreferences {
  private final Map<String,Object> data = new HashMap<>();
  public boolean failNextCommit;
  public Map<String,?> getAll() {return new HashMap<>(data);}
  public String getString(String key,String def) {Object v=data.get(key);return v instanceof String?(String)v:def;}
  public Editor edit() {return new Editor();}
  public final class Editor {
    private String key; private String value; private boolean remove;
    public Editor putString(String k,String v) {key=k;value=v;remove=false;return this;}
    public Editor remove(String k) {key=k;remove=true;return this;}
    public boolean commit() {if(failNextCommit){failNextCommit=false;return false;}if(remove)data.remove(key);else data.put(key,value);return true;}
  }
}''',
'android/os/SystemClock.java': r'''package android.os;
public class SystemClock {public static long now=1000;public static long elapsedRealtime(){return now;}}''',
'org/telegram/messenger/UserConfig.java': r'''package org.telegram.messenger;
public class UserConfig {public static final int MAX_ACCOUNT_COUNT=4;}''',
'org/telegram/messenger/FileLog.java': r'''package org.telegram.messenger;
public class FileLog {public static void e(Throwable e){throw new RuntimeException(e);}}''',
'android/content/Context.java': r'''package android.content;
public class Context { public static final int MODE_PRIVATE=0;
  public final SharedPreferences prefs=new SharedPreferences();
  public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
}''',
'org/json/JSONObject.java': r'''package org.json;
import java.util.*;
public class JSONObject {
  private static final Map<String,JSONObject> encoded=new HashMap<>();
  private static int next;
  private final Map<String,Object> data=new HashMap<>();
  public JSONObject() {}
  public JSONObject(String value) {JSONObject old=encoded.get(value);if(old==null)throw new IllegalArgumentException();data.putAll(old.data);}
  public JSONObject put(String k,Object v){data.put(k,v);return this;}
  public String optString(String k){return optString(k,"");}
  public String optString(String k,String d){Object v=data.get(k);return v==null?d:v.toString();}
  public long optLong(String k){return optLong(k,0);}
  public long optLong(String k,long d){Object v=data.get(k);return v instanceof Number?((Number)v).longValue():d;}
  public int optInt(String k){return optInt(k,0);}
  public int optInt(String k,int d){Object v=data.get(k);return v instanceof Number?((Number)v).intValue():d;}
  public boolean optBoolean(String k){return Boolean.TRUE.equals(data.get(k));}
  public boolean has(String k){return data.containsKey(k);}
  public JSONObject optJSONObject(String k){Object v=data.get(k);return v instanceof JSONObject?(JSONObject)v:null;}
  public JSONArray optJSONArray(String k){Object v=data.get(k);return v instanceof JSONArray?(JSONArray)v:null;}
  public String toString(){String s="encoded-"+(++next);JSONObject copy=new JSONObject();copy.data.putAll(data);encoded.put(s,copy);return s;}
}''',
'org/json/JSONArray.java': r'''package org.json;
import java.util.*;
public class JSONArray {
  private final List<Object> data=new ArrayList<>();
  public JSONArray put(Object v){data.add(v);return this;}
  public int length(){return data.size();}
  public JSONObject optJSONObject(int i){return i>=0&&i<data.size()&&data.get(i) instanceof JSONObject?(JSONObject)data.get(i):null;}
}''',
'org/telegram/messenger/ApplicationLoader.java': r'''package org.telegram.messenger;
public class ApplicationLoader {public static final android.content.Context applicationContext=new android.content.Context();}''',
'org/telegram/messenger/AndroidUtilities.java': r'''package org.telegram.messenger;
public class AndroidUtilities {public static void runOnUIThread(Runnable r){r.run();}}''',
'org/telegram/messenger/NotificationCenter.java': r'''package org.telegram.messenger;
public class NotificationCenter {public static int waysGramPinError=12, errors;
  private static final NotificationCenter instance=new NotificationCenter();
  public static NotificationCenter getInstance(int a){return instance;}
  public void postNotificationName(int id,Object...args){errors++;}
}''',
'org/telegram/messenger/MediaDataController.java': r'''package org.telegram.messenger;
public class MediaDataController {public static int refreshes;public static long lastDialog;
  private static final MediaDataController instance=new MediaDataController();
  public static MediaDataController getInstance(int a){return instance;}
  public void loadPinnedMessages(long id,int max,int fallback){refreshes++;lastDialog=id;}
}''',
'org/telegram/tgnet/TLObject.java': r'''package org.telegram.tgnet; public class TLObject {}''',
'org/telegram/tgnet/TLRPC.java': r'''package org.telegram.tgnet;
import java.util.*;
public class TLRPC {
 public static class InputPeer {public long user_id,chat_id,channel_id;}
 public static class TL_inputPeerSelf extends InputPeer {} public static class TL_inputPeerUser extends InputPeer {}
 public static class TL_inputPeerChat extends InputPeer {} public static class TL_inputPeerChannel extends InputPeer {}
 public static class TL_messages_updatePinnedMessage extends TLObject {public InputPeer peer;public int id;public boolean pm_oneside,unpin,silent;}
 public static class TL_messages_unpinAllMessages extends TLObject {public InputPeer peer;public int flags,top_msg_id;public Object saved_peer_id;}
 public static class TL_messages_search extends TLObject {public InputPeer peer;public Object filter,from_id,saved_peer_id;public String q;
   public int top_msg_id,min_date,max_date,add_offset,max_id,min_id,limit,offset_id;public List<Object> saved_reaction=new ArrayList<>();}
 public static class TL_inputMessagesFilterPinned {}
 public static class Message {public int id;public boolean pinned;}
 public static class User {} public static class Chat {}
 public static class TL_updates extends TLObject {public int date;}
 public static class TL_messages_affectedHistory extends TLObject {public int pts,pts_count,offset;}
 public static class TL_messages_messagesSlice extends TLObject {public int count;public List<Message> messages=new ArrayList<>();public List<User> users=new ArrayList<>();public List<Chat> chats=new ArrayList<>();}
}''',
'org/telegram/tgnet/WaysGramPoller.java': r'''package org.telegram.tgnet;
public class WaysGramPoller {public static boolean running=true;public static boolean isRunning(int a){return running;}}''',
'org/telegram/tgnet/WaysGramTransport.java': r'''package org.telegram.tgnet;
public class WaysGramTransport {public static int nextPts(int a){return 1;}}''',
'org/telegram/tgnet/WaysGramBotFather.java': r'''package org.telegram.tgnet;
public class WaysGramBotFather {public static boolean is(long id){return false;}}''',
'org/telegram/tgnet/WaysGramChats.java': r'''package org.telegram.tgnet;
import org.json.*;import java.util.*;
public class WaysGramChats {
 public static long channelServer(long id){return id-1000000;} public static long channelTg(long id){return id+1000000;}
 public static int serverGroupId(int id){return id;} public static int nextChannelPts(int a,long b){return 1;}
 public static TLRPC.Chat toChat(JSONObject x,long id){return new TLRPC.Chat();}
 public static TLRPC.Chat toChannel(JSONObject x,long id,boolean joined){return new TLRPC.Chat();}
 public static Set<Long> joinedChannels(int a){return new HashSet<>();}
 public static TLRPC.Message groupMessage(JSONObject m,long id,long self){return new TLRPC.Message();}
 public static TLRPC.Message channelMessage(JSONObject m,long id,long self){return new TLRPC.Message();}
 public static class Snapshot {public List<TLRPC.User> users=new ArrayList<>();public void addUsers(JSONArray a){}}
}''',
'org/telegram/tgnet/WaysGramMapper.java': r'''package org.telegram.tgnet;
import org.json.*; public class WaysGramMapper {
 public static TLRPC.User toSelfUser(JSONObject j,String s){return new TLRPC.User();}
 public static TLRPC.User toUser(JSONObject j){return new TLRPC.User();}
 public static TLRPC.Message toMessage(JSONObject j,long id,int account){return new TLRPC.Message();}
}''',
'org/telegram/tgnet/WaysGramMedia.java': r'''package org.telegram.tgnet;
import org.json.*;public class WaysGramMedia {public static TLRPC.Message attach(int a,TLRPC.Message m,JSONObject j){return m;}}''',
'org/telegram/tgnet/WaysGramMessages.java': r'''package org.telegram.tgnet;
public class WaysGramMessages {
 public static TLRPC.Message fetchPrivate(int a,long t,int id,long self,String token){return null;}
 public static TLRPC.Message fetchGroup(int a,long t,int id,long self,String token){return null;}
 public static TLRPC.Message fetchChannel(int a,long t,int id,long self,String token){return null;}
}''',
'org/telegram/tgnet/WaysGramApi.java': r'''package org.telegram.tgnet;
import org.json.*;import java.util.*;
public class WaysGramApi {
 public static class ApiException extends Exception {public final int status;public final String detail;public ApiException(int s,String d){status=s;detail=d;}}
 public static String token="session-A";public static boolean compatible=true,switchOnPost=false;public static int failPost=0,posts=0,applied=0;
 public static String firstKey,lastKey;public static int firstMax,lastMax;public static long firstSnapshot,lastSnapshot;
 public static final Set<String> committed=new HashSet<>();
 public static String getToken(int a){return token;}
 public static JSONObject request(String method,String path,JSONObject body,String token,int timeout) throws ApiException {return request(method,path,body,token,timeout,null);}
 public static JSONObject request(String method,String path,JSONObject body,String token,int timeout,String key) throws ApiException {
  if(path.equals("/api/pins/capabilities"))return new JSONObject().put("version",2).put("one_side",true).put("keyset_paging",true).put("clear_bounded",true).put("clear_snapshot",compatible);
  if(path.startsWith("/api/pins/v2?"))return new JSONObject().put("pinned_messages",new JSONArray().put(new JSONObject().put("message_id",44))).put("total_count",1).put("snapshot_event_id",123L);
  if(path.equals("/api/pins/clear/v2")) {
   posts++;lastKey=key;lastMax=body.optInt("max_id");lastSnapshot=body.optLong("snapshot_event_id");
   if(firstKey==null){firstKey=key;firstMax=lastMax;firstSnapshot=lastSnapshot;}
   if(failPost==-1000){if(committed.add(key))applied++;failPost=0;throw new ApiException(-1000,"NETWORK_ERROR");}
   if(failPost!=0)throw new ApiException(failPost,"REJECTED");
   if(committed.add(key))applied++;
   if(switchOnPost){WaysGramApi.token="session-B";switchOnPost=false;}
   return new JSONObject().put("status","cleared");
  }
  return new JSONObject();
 }
}''',
'org/telegram/tgnet/PinRecoveryTest.java': r'''package org.telegram.tgnet;
import android.content.*;import org.json.*;import org.telegram.messenger.*;
public class PinRecoveryTest {
 static void ok(boolean v,String message){if(!v)throw new AssertionError(message);}
 static TLRCtor ctor(int id){return new TLRCtor(id);}
 static class TLRCtor {TLRPC.TL_messages_unpinAllMessages req=new TLRPC.TL_messages_unpinAllMessages();
  TLRCtor(int id){TLRPC.TL_inputPeerUser p=new TLRPC.TL_inputPeerUser();p.user_id=id;req.peer=p;}}
 static SharedPreferences prefs(){return ApplicationLoader.applicationContext.prefs;}
 static String name(int id){return "pending_0_dm_"+id;}
 static void pending(int id,int error) throws Exception {
  WaysGramApi.failPost=error;
  try{WaysGramPins.clear(0,ctor(id).req,7,WaysGramApi.token);throw new AssertionError("expected failure");}
  catch(WaysGramApi.ApiException e){ok(e.status==error,"wrong error");}
  ok(prefs().getString(name(id),null)!=null,"not durable");
 }
 public static void main(String[] args) throws Exception {
  pending(10,-1000);ok(WaysGramApi.applied==1,"initial uncertain write not applied");
  String key=WaysGramApi.firstKey;int before=WaysGramApi.posts;
  WaysGramPoller.running=false;WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(WaysGramApi.posts==before,"stopped poller replayed");
  WaysGramPoller.running=true;WaysGramApi.token="session-B";
  WaysGramPins.recoverPendingClear(0,"session-B",null);
  ok(WaysGramApi.posts==before && prefs().getString(name(10),null)!=null,"different session replayed");
  WaysGramApi.token="session-A";WaysGramApi.compatible=false;
  WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(WaysGramApi.posts==before,"incompatible server replayed");
  WaysGramApi.compatible=true;String cursor=WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(cursor.equals(name(10)),"wrong cursor");
  ok(WaysGramApi.posts==before+1 && key.equals(WaysGramApi.lastKey),"key changed");
  ok(WaysGramApi.firstMax==WaysGramApi.lastMax && WaysGramApi.firstSnapshot==WaysGramApi.lastSnapshot,"snapshot changed");
  ok(WaysGramApi.applied==1 && prefs().getString(name(10),null)==null,"replay reapplied or retained");
  ok(MediaDataController.refreshes==1 && MediaDataController.lastDialog==10,"no refresh");
  WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(WaysGramApi.posts==before+1,"created a new operation without pending");
  pending(11,503);pending(12,503);WaysGramApi.failPost=503;
  String first=WaysGramPins.recoverPendingClear(0,"session-A",null);
  String second=WaysGramPins.recoverPendingClear(0,"session-A",first);
  ok(first.equals(name(11)) && second.equals(name(12)),"round robin starvation");
  ok(prefs().getString(name(11),null)!=null && prefs().getString(name(12),null)!=null,"transient error removed state");
  WaysGramApi.failPost=403;int errors=NotificationCenter.errors;
  WaysGramPins.recoverPendingClear(0,"session-A",name(12));
  ok(prefs().getString(name(11),null)==null && NotificationCenter.errors==errors+1,"permanent refusal did not stop and notify");
  JSONObject old=new JSONObject(prefs().getString(name(12),null));old.put("created",System.currentTimeMillis()-7L*24*60*60*1000);
  prefs().edit().putString(name(12),old.toString()).commit();before=WaysGramApi.posts;
  WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(WaysGramApi.posts==before && prefs().getString(name(12),null)!=null,"expired pending replayed or silently discarded");
  WaysGramApi.failPost=0;
  try{WaysGramPins.clear(0,ctor(12).req,7,"session-A");throw new AssertionError("expired not reported");}
  catch(WaysGramApi.ApiException e){ok("PIN_CLEAR_PENDING_EXPIRED".equals(e.detail),"wrong expiry error");}
  ok(prefs().getString(name(12),null)==null,"expired pending not removed on explicit request");
  pending(13,-1000);String inFlightKey=WaysGramApi.lastKey;int applied=WaysGramApi.applied, refreshes=MediaDataController.refreshes;
  WaysGramApi.switchOnPost=true;
  WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(prefs().getString(name(13),null)!=null && MediaDataController.refreshes==refreshes,"session switch touched new session");
  WaysGramApi.token="session-A";
  WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(prefs().getString(name(13),null)==null && WaysGramApi.lastKey.equals(inFlightKey) && WaysGramApi.applied==applied,"session switch caused duplicate");
  pending(14,429);WaysGramApi.failPost=429;
  WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(prefs().getString(name(14),null)!=null,"rate limit discarded pending");
  WaysGramApi.failPost=0;WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(prefs().getString(name(14),null)==null,"rate limit recovery failed");
  pending(15,503);JSONObject future=new JSONObject(prefs().getString(name(15),null));future.put("created",System.currentTimeMillis()+1000000);
  prefs().edit().putString(name(15),future.toString()).commit();before=WaysGramApi.posts;
  WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(WaysGramApi.posts==before && prefs().getString(name(15),null)!=null,"clock rollback replayed");
  before=WaysGramApi.posts;WaysGramApi.failPost=403;
  try{WaysGramPins.clear(0,ctor(17).req,7,"session-A");throw new AssertionError("missing permission error");}
  catch(WaysGramApi.ApiException e){ok(e.status==403,"wrong permission error");}
  ok(prefs().getString(name(17),null)==null,"foreground permission error left pending");
  before=WaysGramApi.posts;WaysGramApi.failPost=0;WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(WaysGramApi.posts==before,"replayed foreground permission rejection");
  prefs().failNextCommit=true;before=WaysGramApi.posts;WaysGramApi.failPost=0;
  try{WaysGramPins.clear(0,ctor(16).req,7,"session-A");throw new AssertionError("missing storage failure");}
  catch(WaysGramApi.ApiException e){ok("PIN_CLEAR_STATE_SAVE_FAILED".equals(e.detail),"wrong storage failure");}
  ok(WaysGramApi.posts==before && prefs().getString(name(16),null)==null,"sent clear without durable state");
  TLRPC.TL_messages_unpinAllMessages g=new TLRPC.TL_messages_unpinAllMessages();
  TLRPC.TL_inputPeerChat gp=new TLRPC.TL_inputPeerChat();gp.chat_id=22;g.peer=gp;WaysGramApi.failPost=503;
  try{WaysGramPins.clear(0,g,7,"session-A");throw new AssertionError("missing group failure");}catch(WaysGramApi.ApiException e){ok(e.status==503,"wrong group error");}
  WaysGramApi.failPost=0;WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(prefs().getString("pending_0_group_22",null)==null && MediaDataController.lastDialog==-22,"group recovery target wrong");
  TLRPC.TL_messages_unpinAllMessages c=new TLRPC.TL_messages_unpinAllMessages();
  TLRPC.TL_inputPeerChannel cp=new TLRPC.TL_inputPeerChannel();cp.channel_id=1000033;c.peer=cp;WaysGramApi.failPost=503;
  try{WaysGramPins.clear(0,c,7,"session-A");throw new AssertionError("missing channel failure");}catch(WaysGramApi.ApiException e){ok(e.status==503,"wrong channel error");}
  WaysGramApi.failPost=0;WaysGramPins.recoverPendingClear(0,"session-A",null);
  ok(prefs().getString("pending_0_channel_33",null)==null && MediaDataController.lastDialog==-1000033,"channel recovery target wrong");
  pending(18,-1000);before=WaysGramApi.posts;android.os.SystemClock.now=1000;
  WaysGramTransport.schedulePendingPinRecovery(0);WaysGramTransport.schedulePendingPinRecovery(0);
  ok(WaysGramTransport.tasks.size()==1 && WaysGramApi.posts==before,"duplicate or synchronous recovery");
  WaysGramTransport.drain();
  ok(prefs().getString(name(18),null)==null && WaysGramApi.posts==before+1,"scheduled recovery failed");
  pending(19,503);WaysGramApi.failPost=0;android.os.SystemClock.now=2000;
  WaysGramTransport.schedulePendingPinRecovery(0);ok(WaysGramTransport.tasks.isEmpty(),"throttle failed");
  android.os.SystemClock.now=31000;WaysGramTransport.schedulePendingPinRecovery(0);
  before=WaysGramApi.posts;WaysGramApi.token="session-B";WaysGramTransport.drain();
  ok(WaysGramApi.posts==before && prefs().getString(name(19),null)!=null,"queued job ran under new session");
  WaysGramApi.token="session-A";android.os.SystemClock.now=61000;WaysGramTransport.schedulePendingPinRecovery(0);WaysGramTransport.drain();
  ok(prefs().getString(name(19),null)==null,"scheduled retry after session return failed");
  System.out.println("Pin recovery Java fake integration: OK");
 }
}''',
}


def main():
    java = os.environ.get('WG_JAVA') or shutil.which('java')
    javac = shutil.which('javac')
    ecj = os.environ.get('WG_ECJ_JAR')
    if not java or not (javac or ecj):
        raise SystemExit('Requires java + javac, or WG_JAVA and WG_ECJ_JAR')
    with tempfile.TemporaryDirectory(prefix='waysgram-pin-test-') as tmp:
        out = Path(tmp)
        for name, contents in SOURCES.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding='utf-8')
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        with zipfile.ZipFile(__import__('io').BytesIO(data)) as z:
            base = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/tgnet/'
            source = z.read(base + 'WaysGramPins.java')
            transport = z.read(base + 'WaysGramTransport.java').decode('utf-8')
        (out / 'org/telegram/tgnet/WaysGramPins.java').write_bytes(source)
        start = transport.index('    private static final boolean[] recoveringPins')
        end = transport.index('    /** Synthetic update counter.', start)
        # Compile the exact production scheduler, with its queue and Android dependencies faked.
        wrapper = """package org.telegram.tgnet;
import android.text.TextUtils;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.FileLog;
public class WaysGramTransport {
  static final java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<>();
  static java.util.concurrent.Executor pinQueue(int account){return task -> tasks.add(task);}
  static void drain(){while(!tasks.isEmpty())tasks.remove().run();}
  public static int nextPts(int account){return 1;}
""" + transport[start:end] + "\n}"
        (out / 'org/telegram/tgnet/WaysGramTransport.java').write_text(wrapper, encoding='utf-8')
        files = [str(p) for p in out.rglob('*.java')]
        cmd = [javac, '-source', '8', '-target', '8', '-d', str(out)] if javac else [java, '-jar', ecj, '-1.8', '-proc:none', '-d', str(out)]
        subprocess.run(cmd + files, check=True)
        subprocess.run([java, '-cp', str(out), 'org.telegram.tgnet.PinRecoveryTest'], check=True)


if __name__ == '__main__':
    main()
