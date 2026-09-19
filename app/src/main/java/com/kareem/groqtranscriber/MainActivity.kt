package com.kareem.groqtranscriber
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  @Suppress("DEPRECATION") val shared=if(intent?.action==Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
  setContent { MaterialTheme(colorScheme=darkColorScheme()) { Screen(shared) } }
 }
 @Composable private fun Screen(initialUri:Uri?) {
  val prefs=remember{getSharedPreferences("local",MODE_PRIVATE)}
  var apiKey by remember{mutableStateOf(prefs.getString("groq_key","")?:"")}
  var audioUri by remember{mutableStateOf(initialUri)}
  var transcript by remember{mutableStateOf("")}
  var status by remember{mutableStateOf(if(initialUri==null)"Choose or share an audio file" else fileName(initialUri))}
  var busy by remember{mutableStateOf(false)}
  val scope=rememberCoroutineScope()
  val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){if(it!=null){audioUri=it;status=fileName(it);transcript=""}}
  Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
   Spacer(Modifier.height(16.dp)); Text("Groq Transcriber",style=MaterialTheme.typography.headlineMedium)
   Text("Whisper Large V3 • Arabic + English auto-detect",color=MaterialTheme.colorScheme.onSurfaceVariant)
   OutlinedTextField(apiKey,{apiKey=it.trim();prefs.edit().putString("groq_key",apiKey).apply()},label={Text("Groq API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
   Button({picker.launch(arrayOf("audio/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(audioUri==null)"Select audio" else "Change audio")}
   if(audioUri!=null) Button(onClick={busy=true;status="Transcribing…";transcript="";scope.launch{runCatching{transcribe(audioUri!!,apiKey)}.onSuccess{transcript=it;status="Done"}.onFailure{status="Error: "+(it.message?:"Unknown error")};busy=false}},enabled=!busy&&apiKey.startsWith("gsk_"),modifier=Modifier.fillMaxWidth()){Text(if(busy)"Working…" else "Transcribe")}
   if(busy) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(status)
   if(transcript.isNotBlank()){HorizontalDivider();Text(transcript,style=MaterialTheme.typography.bodyLarge);OutlinedButton(onClick={(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcript",transcript))},modifier=Modifier.fillMaxWidth()){Text("Copy transcript")}}
  }}
 }
 private fun fileName(uri:Uri):String{contentResolver.query(uri,null,null,null,null)?.use{c->val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0&&c.moveToFirst())return c.getString(i)};return "audio"}
 private suspend fun transcribe(uri:Uri,key:String):String=withContext(Dispatchers.IO){
  require(key.isNotBlank()){"Enter Groq API key"};val boundary="----Boundary"+UUID.randomUUID().toString().replace("-","")
  val conn=(URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=30000;readTimeout=180000;setRequestProperty("Authorization","Bearer $key");setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")}
  DataOutputStream(conn.outputStream).use{out->
   fun field(n:String,v:String){out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n");out.write(v.toByteArray(Charsets.UTF_8));out.writeBytes("\r\n")}
   field("model","whisper-large-v3");field("response_format","json");field("temperature","0")
   val name=fileName(uri).replace("\"","");val mime=contentResolver.getType(uri)?:"audio/mp4"
   out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n");contentResolver.openInputStream(uri)!!.use{it.copyTo(out)};out.writeBytes("\r\n--$boundary--\r\n")
  }
  val code=conn.responseCode;val body=(if(code in 200..299)conn.inputStream else conn.errorStream).bufferedReader().use{it.readText()};if(code !in 200..299)error("Groq HTTP $code: $body");JSONObject(body).optString("text").ifBlank{error("No transcript returned")}
 }
}
