package dev.glowcam

import android.app.AlertDialog
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/** [KeyValueStorage] on top of SharedPreferences. */
class PrefsStorage(private val prefs: SharedPreferences) : KeyValueStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * Style picker and editor that live on top of the camera preview.
 * - style button: open/close the strip of styles; tap a style to apply it
 * - long-press a style (or "+ new"): edit panel with live preview; Save / Cancel / Delete
 * The applied style's name is shown at the bottom centre; the repository remembers the last one.
 */
class StyleController(
    private val activity: ComponentActivity,
    private val repo: StyleRepository,
    /** Called whenever the effective beauty values change (applied style or live edit). */
    private val onParams: (BeautyParams) -> Unit,
) {
    private class Session(val base: Style, val isNew: Boolean, var name: String)

    private val strip = activity.findViewById<View>(R.id.style_strip)
    private val list = activity.findViewById<LinearLayout>(R.id.style_list)
    private val nameLabel = activity.findViewById<TextView>(R.id.style_name)
    private val controls = activity.findViewById<View>(R.id.controls)
    private val editor = activity.findViewById<View>(R.id.style_editor)
    private val editorName = activity.findViewById<TextView>(R.id.editor_name)
    private val seekSmooth = activity.findViewById<SeekBar>(R.id.seek_smooth)
    private val seekWhiten = activity.findViewById<SeekBar>(R.id.seek_whiten)
    private val seekRosy = activity.findViewById<SeekBar>(R.id.seek_rosy)
    private val deleteButton = activity.findViewById<Button>(R.id.btn_editor_delete)
    private val defaultButton = activity.findViewById<Button>(R.id.btn_editor_default)
    private var session: Session? = null

    /** True while the style editor is open (the camera must not shoot or refocus meanwhile). */
    val isEditing: Boolean get() = session != null

    /** True while the strip or the editor is open (the back button closes them first). */
    val isOpen: Boolean get() = session != null || strip.visibility == View.VISIBLE

    init {
        activity.findViewById<View>(R.id.btn_style).setOnClickListener { toggleStrip() }
        editorName.setOnClickListener { askName() }
        activity.findViewById<View>(R.id.btn_editor_save).setOnClickListener { save() }
        activity.findViewById<View>(R.id.btn_editor_cancel).setOnClickListener { cancel() }
        deleteButton.setOnClickListener { confirmDelete() }
        defaultButton.setOnClickListener { setSliders(DefaultStyles.recommended()) }

        val live = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (session != null) onParams(slidersToParams())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        seekSmooth.setOnSeekBarChangeListener(live)
        seekWhiten.setOnSeekBarChangeListener(live)
        seekRosy.setOnSeekBarChangeListener(live)

        applyCurrent()
        rebuildStrip()
    }

    /** Back button: closes the editor (discarding changes) or the strip. Returns true if it did something. */
    fun closeIfOpen(): Boolean {
        if (session != null) {
            cancel()
            return true
        }
        if (strip.visibility == View.VISIBLE) {
            strip.visibility = View.GONE
            return true
        }
        return false
    }

    private fun applyCurrent() {
        val s = repo.state.selected
        nameLabel.text = s.name
        onParams(s.toBeautyParams())
    }

    private fun toggleStrip() {
        if (session != null) return
        strip.visibility = if (strip.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun rebuildStrip() {
        list.removeAllViews()
        val dp = activity.resources.displayMetrics.density
        fun chip(text: String, selected: Boolean): TextView = TextView(activity).apply {
            this.text = text
            textSize = 15f
            setPadding((16 * dp).toInt(), (9 * dp).toInt(), (16 * dp).toInt(), (9 * dp).toInt())
            setBackgroundResource(if (selected) R.drawable.chip_bg_selected else R.drawable.chip_bg)
            setTextColor(if (selected) 0xFF1F3A4D.toInt() else 0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (8 * dp).toInt() }
        }
        for (style in repo.state.all) {
            list.addView(
                chip(style.name, style.id == repo.state.selectedId).apply {
                    setOnClickListener {
                        repo.select(style.id)
                        applyCurrent()
                        rebuildStrip()
                        strip.visibility = View.GONE
                    }
                    if (style.id != DefaultStyles.ORIGINAL_ID) {
                        setOnLongClickListener {
                            openEditor(style, isNew = false)
                            true
                        }
                    }
                },
            )
        }
        list.addView(
            chip(activity.getString(R.string.add_style), false).apply {
                setOnClickListener { openEditor(repo.draft(repo.state.selected), isNew = true) }
            },
        )
    }

    private fun openEditor(style: Style, isNew: Boolean) {
        session = Session(style, isNew, style.name)
        setSliders(style.toBeautyParams())
        editorName.text = style.name
        // Built-in style: can be restored to its default values. Saved custom style: can be deleted instead.
        val builtIn = !isNew && style.id == DefaultStyles.PORTRAIT_ID
        defaultButton.visibility = if (builtIn) View.VISIBLE else View.GONE
        deleteButton.visibility = if (!isNew && !builtIn) View.VISIBLE else View.GONE
        strip.visibility = View.GONE
        controls.visibility = View.GONE
        editor.visibility = View.VISIBLE
        onParams(slidersToParams()) // preview shows this style while it is being adjusted
    }

    private fun setSliders(p: BeautyParams) {
        seekSmooth.progress = Math.round(p.smooth * BeautyParams.MAX_PROGRESS)
        seekWhiten.progress = Math.round(p.whiten * BeautyParams.MAX_PROGRESS)
        seekRosy.progress = Math.round(p.rosy * BeautyParams.MAX_PROGRESS)
    }

    private fun closeEditor() {
        session = null
        editor.visibility = View.GONE
        controls.visibility = View.VISIBLE
    }

    private fun slidersToParams() = BeautyParams.fromProgress(seekSmooth.progress, seekWhiten.progress, seekRosy.progress)

    private fun save() {
        val s = session ?: return
        repo.save(s.base.withParams(slidersToParams()).copy(name = s.name))
        closeEditor()
        applyCurrent()
        rebuildStrip()
    }

    private fun cancel() {
        if (session == null) return
        closeEditor()
        applyCurrent() // back to the style that was applied before editing
    }

    private fun askName() {
        val s = session ?: return
        val input = EditText(activity).apply {
            setText(s.name)
            setSelection(text.length)
            setSingleLine()
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.style_name_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                s.name = input.text.toString().trim().ifEmpty { s.name }
                editorName.text = s.name
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        val s = session ?: return
        AlertDialog.Builder(activity)
            .setTitle(R.string.delete_style_title)
            .setMessage(s.base.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.delete(s.base.id)
                closeEditor()
                applyCurrent()
                rebuildStrip()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
