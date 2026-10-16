package org.catrobat.catroid.ui.recyclerview.fragment

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.R
import org.catrobat.catroid.common.FlavoredConstants
import org.catrobat.catroid.common.FlavoredConstants.DEFAULT_ROOT_DIRECTORY
import org.catrobat.catroid.databinding.FragmentMainMenuPcBinding
import org.catrobat.catroid.io.ProjectAndSceneScreenshotLoader
import org.catrobat.catroid.io.asynctask.ProjectLoader
import org.catrobat.catroid.io.asynctask.ProjectLoader.ProjectLoadListener
import org.catrobat.catroid.io.asynctask.loadProject
import org.catrobat.catroid.stage.StageActivity
import org.catrobat.catroid.ui.CommunityWebViewActivity
import org.catrobat.catroid.ui.MainMenuActivity
import org.catrobat.catroid.ui.ProjectActivity
import org.catrobat.catroid.ui.WebViewActivity
import org.catrobat.catroid.ui.dialogs.NewProjectDialogFragment
import org.catrobat.catroid.utils.FileMetaDataExtractor
import org.catrobat.catroid.utils.ToastUtil
import org.catrobat.catroid.utils.Utils
import org.koin.android.ext.android.inject
import java.io.File

class MainMenuPcFragment : Fragment(), View.OnClickListener, ProjectLoadListener {

    private val projectManager: ProjectManager by inject()
    private var _binding: FragmentMainMenuPcBinding? = null
    private val binding get() = _binding!!

    private var currentProject: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.i(STARTUP_TAG, "stage.begin=main-menu-pc-fragment.onCreateView")
        return try {
            _binding = FragmentMainMenuPcBinding.inflate(inflater, container, false)
            Log.i(STARTUP_TAG, "stage.complete=main-menu-pc-fragment.onCreateView")
            binding.root
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "stage.failed=main-menu-pc-fragment.onCreateView", error)
            throw error
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.i(STARTUP_TAG, "stage.begin=main-menu-pc-fragment.onViewCreated")
        view.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.width <= 0 || view.height <= 0) return
                Log.i(STARTUP_TAG, "view.first-layout=main-menu-pc-fragment size=${view.width}x${view.height} shown=${view.isShown}")
                (activity as? MainMenuActivity)?.onStartupMenuFirstLayout("MainMenuPcFragment")
                try {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } catch (error: Exception) {
                    Log.w(STARTUP_TAG, "view.layout-listener-remove-failed=main-menu-pc-fragment", error)
                }
            }
        })

        try {
            binding.editProject.setOnClickListener(this)
            binding.projectImageView.setOnClickListener(this)
            binding.playProject.setOnClickListener(this)
            binding.btnWeb.setOnClickListener(this)
            binding.btnCommunity.setOnClickListener(this)
            binding.btnNewProjectFab.setOnClickListener(this)

            if (savedInstanceState == null) {
                childFragmentManager.beginTransaction()
                    .replace(R.id.pc_project_list_container, ProjectListFragment())
                    .commit()
            }
            Log.i(STARTUP_TAG, "stage.complete=main-menu-pc-fragment.onViewCreated")
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "stage.failed=main-menu-pc-fragment.onViewCreated", error)
            throw error
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setTitle(R.string.app_name)

        loadCurrentProjectData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadCurrentProjectData() {
        currentProject = Utils.getCurrentProjectName(requireContext())

        val projectDir = File(
            DEFAULT_ROOT_DIRECTORY,
            FileMetaDataExtractor.encodeSpecialCharsForFileSystem(currentProject)
        )

        CoroutineScope(Dispatchers.IO).launch {
            loadProject(projectDir, requireContext())
        }

        val loader = ProjectAndSceneScreenshotLoader(CURRENT_THUMBNAIL_SIZE, CURRENT_THUMBNAIL_SIZE)
        loader.loadAndShowScreenshot(
            projectDir.name,
            loader.getScreenshotSceneName(projectDir),
            false,
            binding.projectImageView
        )
    }

    override fun onClick(view: View) {
        when (view.id) {

            R.id.projectImageView,
            R.id.editProject -> {
                val projectDir = File(
                    DEFAULT_ROOT_DIRECTORY,
                    FileMetaDataExtractor.encodeSpecialCharsForFileSystem(currentProject)
                )
                ProjectLoader(projectDir, requireContext())
                    .setListener(this)
                    .loadProjectAsync()
            }


            R.id.playProject -> {
                StageActivity.handlePlayButton(projectManager, activity)
            }


            R.id.btnWeb -> {
                startActivity(Intent(activity, WebViewActivity::class.java))
            }


            R.id.btnCommunity -> {
                val communityIntent = Intent(activity, CommunityWebViewActivity::class.java)
                startActivity(communityIntent)
            }


            R.id.btnNewProjectFab -> {
                val dialog = NewProjectDialogFragment()
                dialog.show(parentFragmentManager, NewProjectDialogFragment.TAG)
            }
        }
    }


    override fun onLoadFinished(success: Boolean) {
        if (success) {
            val intent = Intent(activity, ProjectActivity::class.java)
            intent.putExtra(
                ProjectActivity.EXTRA_FRAGMENT_POSITION,
                ProjectActivity.FRAGMENT_SCENES
            )
            startActivity(intent)
        } else {
            ToastUtil.showError(activity, R.string.error_load_project)
        }
    }

    companion object {
        val TAG: String = MainMenuPcFragment::class.java.simpleName
        private const val STARTUP_TAG = "NewCatroidStartup"
        private const val CURRENT_THUMBNAIL_SIZE = 500
    }
}
