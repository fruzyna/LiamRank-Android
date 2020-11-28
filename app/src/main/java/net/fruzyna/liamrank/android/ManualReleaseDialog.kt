package net.fruzyna.liamrank.android

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ManualReleaseDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val mainActivity = (activity as MainActivity)
            val builder = AlertDialog.Builder(it)
            builder.setTitle("Name a Release")

            val inflater = requireActivity().layoutInflater
            builder.setView(inflater.inflate(R.layout.dialog_manual_release, null))
                .setPositiveButton("SEARCH") { _,_ ->
                    val release = dialog!!.findViewById<EditText>(R.id.edit_manual_release).text.toString()
                    CoroutineScope(Dispatchers.Main).launch { mainActivity.init(release) }
                }
                .setNegativeButton("USE LATEST") { _,_ ->
                    CoroutineScope(Dispatchers.Main).launch { mainActivity.init("latest") }
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    // use latest if clicked off
    override fun onCancel(dialog: DialogInterface) {
        CoroutineScope(Dispatchers.Main).launch { (activity as MainActivity).init("latest") }
    }
}