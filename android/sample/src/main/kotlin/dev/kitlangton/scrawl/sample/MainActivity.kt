package dev.kitlangton.scrawl.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kitlangton.scrawl.Scrawl
import dev.kitlangton.scrawl.ScrawlCaptureMethod
import dev.kitlangton.scrawl.ScrawlScreen

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        column.addView(TextView(this).apply {
            text = "Scrawl Sample"
            textSize = 28f
            setTextColor(Color.BLACK)
        })
        column.addView(TextView(this).apply {
            text = if (Scrawl.isConfigured) "Paired: hold three fingers to record" else "Not paired"
            contentDescription = "Pairing status"
        })
        val note = EditText(this).apply { hint = "Type a note" }
        column.addView(note)
        column.addView(Button(this).apply {
            text = "Show keyboard"
            setOnClickListener {
                note.requestFocus()
                note.post {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        note.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
                    } else {
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .showSoftInput(note, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        })
        column.addView(Button(this).apply {
            text = "Show dialog"
            setOnClickListener {
                Scrawl.event("Dialog opened")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("A dialog")
                    .setMessage("Dialogs are recorded too.")
                    .setPositiveButton("OK") { _, _ -> Scrawl.event("Dialog confirmed") }
                    .show()
            }
        })
        column.addView(Button(this).apply {
            text = "Open Compose"
            setOnClickListener { startActivity(Intent(this@MainActivity, ComposeActivity::class.java)) }
        })
        column.addView(Button(this).apply {
            text = "Open detail"
            setOnClickListener { startActivity(Intent(this@MainActivity, DetailActivity::class.java)) }
        })
        column.addView(Button(this).apply {
            fun label() = "Capture: " + Scrawl.shared.captureMethod.raw
            text = label()
            setOnClickListener {
                Scrawl.shared.captureMethod = if (Scrawl.shared.captureMethod == ScrawlCaptureMethod.AppFrames) ScrawlCaptureMethod.MediaProjection else ScrawlCaptureMethod.AppFrames
                text = label()
            }
        })
        column.addView(Button(this).apply {
            text = "Record (manual)"
            setOnClickListener { Scrawl.shared.toggle() }
        })
        for (i in 1..40) {
            column.addView(TextView(this).apply {
                text = "Row $i · Session #${i * 37 % 100}"
                textSize = 18f
                setPadding(0, 28, 0, 28)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { Scrawl.event("Row $i tapped") }
            })
        }
        val scroll = ScrollView(this).apply { addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        setContentView(scroll)
        ScrawlScreen.attach(scroll, "Home")
    }
}

class DetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "Detail screen"
            textSize = 24f
            setPadding(48, 160, 48, 48)
        }
        setContentView(text)
        ScrawlScreen.attach(text, "Detail")
        Scrawl.context("item", "42")
    }
}
