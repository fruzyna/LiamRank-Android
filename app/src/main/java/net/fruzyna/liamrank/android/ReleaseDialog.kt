package net.fruzyna.liamrank.android

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReleaseDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val mainActivity = (activity as MainActivity)
            val lastUsed = mainActivity.getLastRelease()

            // create base list of releases
            val releases = ArrayList<String>()
            releases.add("Last Used: $lastUsed")
            releases.add("Latest Remote Release")
            releases.add("Master Branch")

            // add any other local releases
            val files = context!!.getExternalFilesDir("")!!.listFiles()
            if (files != null) {
                for (file in files) {
                    val name = file.nameWithoutExtension
                    if (name.startsWith("LiamRank-") &&
                        !name.endsWith("-master") &&
                        !name.endsWith("-$lastUsed") &&
                        !name.endsWith("-")) {
                        releases.add("Cached: ${name.substring(9)}")
                    }
                }
            }

            // build dialog
            val builder = AlertDialog.Builder(it)
            builder.setTitle("Choose Release")
                .setItems(releases.toTypedArray()) { _, which ->
                    // choose release name
                    var release = when (which)  {
                        0 -> lastUsed
                        1 -> "latest"
                        2 -> "master"
                        else -> releases[which-3].substring(8)
                    }

                    // launch app
                    CoroutineScope(Dispatchers.Main).launch { (activity as MainActivity).init(release) }
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}