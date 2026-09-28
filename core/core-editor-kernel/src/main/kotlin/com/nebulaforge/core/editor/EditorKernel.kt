package com.nebulaforge.core.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class TextDocument(val uri: String, val languageId: String, val text: String, val version: Int = 1)

class DocumentModel(initial: TextDocument) {
    private val _document = MutableStateFlow(initial)
    val document: StateFlow<TextDocument> = _document.asStateFlow()
    private val savedVersion = AtomicInteger(initial.version)
    val isDirty: Boolean get() = _document.value.version != savedVersion.get()
    fun replaceText(text: String) { val d=_document.value; _document.value=d.copy(text=text, version=d.version+1) }
    fun markSaved() { savedVersion.set(_document.value.version) }
    /** 记录编辑器外部已发生的修改，不重复执行命令。 */
    fun recordExternalChange(text: String) {
        val d = _document.value
        if (d.text != text) _document.value = d.copy(text = text, version = d.version + 1)
    }
    fun markReloaded(text: String, version: Int = _document.value.version + 1) { _document.value=_document.value.copy(text=text,version=version); savedVersion.set(version) }
}

interface EditCommand { fun execute(document: DocumentModel); fun undo(document: DocumentModel) }
class ReplaceTextCommand(private val before: String, private val after: String): EditCommand { override fun execute(document: DocumentModel)=document.replaceText(after); override fun undo(document: DocumentModel)=document.replaceText(before) }
class CommandStack(private val maxSize:Int=200) {
    private val undo=ArrayDeque<EditCommand>(); private val redo=ArrayDeque<EditCommand>()
    fun push(command: EditCommand, document: DocumentModel){ command.execute(document); undo.addLast(command); if(undo.size>maxSize) undo.removeFirst(); redo.clear() }
    fun undo(document: DocumentModel){ undo.removeLastOrNull()?.also{it.undo(document);redo.addLast(it)} }
    fun redo(document: DocumentModel){ redo.removeLastOrNull()?.also{it.execute(document);undo.addLast(it)} }
    fun canUndo()=undo.isNotEmpty(); fun canRedo()=redo.isNotEmpty()
    fun record(command: EditCommand) { undo.addLast(command); if(undo.size>maxSize) undo.removeFirst(); redo.clear() }
}

interface LspTransport { fun send(message: JSONObject); fun close() }
class LspClient(private val transport:LspTransport) {
    private val ids=AtomicInteger(); private val pending=HashMap<Int,(JSONObject)->Unit>()
    @Synchronized fun request(method:String, params:JSONObject, callback:(JSONObject)->Unit):Int { val id=ids.incrementAndGet(); pending[id]=callback; transport.send(JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params)); return id }
    @Synchronized fun notify(method:String, params:JSONObject){ transport.send(JSONObject().put("jsonrpc","2.0").put("method",method).put("params",params)) }
    @Synchronized fun onMessage(message:JSONObject){ val id=message.optInt("id",Int.MIN_VALUE); if(id!=Int.MIN_VALUE) pending.remove(id)?.invoke(message) }
    fun close()=transport.close()
}
