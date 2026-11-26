/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2023 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.ui.recyclerview.fragment

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.PagerSnapHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.R
import org.catrobat.catroid.common.Constants
import org.catrobat.catroid.common.FlavoredConstants
import org.catrobat.catroid.common.FlavoredConstants.CATEGORY_URL
import org.catrobat.catroid.common.FlavoredConstants.DEFAULT_ROOT_DIRECTORY
import org.catrobat.catroid.common.ProjectData
import org.catrobat.catroid.databinding.FragmentMainMenuBinding
import org.catrobat.catroid.io.ProjectAndSceneScreenshotLoader
import org.catrobat.catroid.io.asynctask.ProjectLoader
import org.catrobat.catroid.io.asynctask.ProjectLoader.ProjectLoadListener
import org.catrobat.catroid.io.asynctask.loadProject
import org.catrobat.catroid.stage.StageActivity
import org.catrobat.catroid.ui.CommunityWebViewActivity
import org.catrobat.catroid.ui.MainMenuActivity
import org.catrobat.catroid.ui.PROJECT_DIR
import org.catrobat.catroid.ui.ProjectActivity
import org.catrobat.catroid.ui.ProjectListActivity
import org.catrobat.catroid.ui.ProjectUploadActivity
import org.catrobat.catroid.ui.WebViewActivity
import org.catrobat.catroid.ui.dialogs.NewProjectDialogFragment
import org.catrobat.catroid.ui.recyclerview.CategoryTitleCallback
import org.catrobat.catroid.ui.recyclerview.FeaturedProjectCallback
import org.catrobat.catroid.ui.recyclerview.IndicatorDecoration
import org.catrobat.catroid.ui.recyclerview.ProjectListener
import org.catrobat.catroid.ui.recyclerview.adapter.CategoriesAdapter
import org.catrobat.catroid.ui.recyclerview.adapter.FeaturedProjectsAdapter
import org.catrobat.catroid.ui.recyclerview.adapter.HorizontalProjectsAdapter
import org.catrobat.catroid.ui.recyclerview.viewmodel.MainFragmentViewModel
import org.catrobat.catroid.utils.FileMetaDataExtractor
import org.catrobat.catroid.utils.ProjectDownloadUtil.setFragment
import org.catrobat.catroid.utils.ToastUtil
import org.catrobat.catroid.utils.Utils
import org.catrobat.catroid.utils.setVisibleOrGone
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class MainMenuFragment : Fragment(),
    ProjectListener,
    View.OnClickListener,
    FeaturedProjectCallback,
    CategoryTitleCallback,
    ProjectLoadListener {

    var currentProject: String? = null
    private lateinit var projectsAdapter: HorizontalProjectsAdapter
    private val viewModel: MainFragmentViewModel by viewModel()
    private val featuredProjectsAdapter: FeaturedProjectsAdapter by inject()
    private val categoriesAdapter: CategoriesAdapter by inject()
    private val projectManager: ProjectManager by inject()
    private var _binding: FragmentMainMenuBinding? = null
    private val binding get() = _binding!!
    private var progressBar: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.i(STARTUP_TAG, "stage.begin=main-menu-fragment.onCreateView")
        return try {
            viewModel.setIsLoading(true)
            _binding = FragmentMainMenuBinding.inflate(inflater, container, false)
            Log.i(STARTUP_TAG, "stage.complete=main-menu-fragment.onCreateView layout=${binding.root.javaClass.simpleName}")
            binding.root
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "stage.failed=main-menu-fragment.onCreateView", error)
            throw error
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.i(STARTUP_TAG, "stage.complete=main-menu-fragment.onViewCreated")
        view.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.width <= 0 || view.height <= 0) return
                Log.i(STARTUP_TAG, "view.first-layout=main-menu-fragment size=${view.width}x${view.height} visibility=${view.visibility} shown=${view.isShown}")
                (activity as? MainMenuActivity)?.onStartupMenuFirstLayout("MainMenuFragment")
                try {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } catch (error: Exception) {
                    Log.w(STARTUP_TAG, "view.layout-listener-remove-failed=main-menu-fragment", error)
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.i(STARTUP_TAG, "stage.begin=main-menu-fragment.onActivityCreated")
        try {
            try {
                progressBar = requireActivity().findViewById<LinearLayout?>(R.id.progress_bar)
                viewModel.isLoading().observe(viewLifecycleOwner, Observer { show ->
                    progressBar?.setVisibleOrGone(show)
                })
            } catch (error: Exception) {
                Log.e(STARTUP_TAG, "main-menu-fragment.progress-observer-failed", error)
            }

            setupFeaturedProjectsRV()
            setupCategoriesRV()
            setupViewVisibility()

            binding.editProject.setOnClickListener(this)
            //binding.uploadProject.setOnClickListener(this)
            binding.newProjectFloatingActionButton.setOnClickListener(this)
            binding.myProjectsTextView.setOnClickListener(this)
            binding.projectImageView.setOnClickListener(this)
            binding.playProject.setOnClickListener(this)
            binding.featuredProjectsTextView.setOnClickListener(this)
            binding.featuredProjectsTextView2.setOnClickListener(this)

            setFragment(this)
            setupProjectsRV()
            viewModel.setIsLoading(false)
            Log.i(STARTUP_TAG, "stage.complete=main-menu-fragment.onActivityCreated")
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "stage.failed=main-menu-fragment.onActivityCreated", error)
            throw error
        }
    }

    private fun setupCategoriesRV() {
        //binding.categoriesRecyclerView.setHasFixedSize(true)
        categoriesAdapter.apply {
            setOnProjectClickCallback(this@MainMenuFragment)
            setOnCategoryTitleClickCallback(this@MainMenuFragment)
        }.let {
            //binding.categoriesRecyclerView.adapter = it
        }
    }

    private fun setupViewVisibility() {
        viewModel.connectionStatusAndFeaturedProjectsAndProjectCategoriesLiveData()
            .observe(viewLifecycleOwner, Observer {
                val isConnectionActive = it.first
                val featuredProjectsList = it.second
                val projectsCategoriesList = it.third

                val showNoInternetLayout = !isConnectionActive &&
                    (featuredProjectsList.isNullOrEmpty() || projectsCategoriesList.isNullOrEmpty())
                //binding.noInternetLayout.setVisibleOrGone(showNoInternetLayout)
                if (showNoInternetLayout) {
                    return@Observer
                }

                //binding.categoriesRecyclerView.setVisibleOrGone(!showNoInternetLayout)

                featuredProjectsAdapter.setItems(featuredProjectsList)
                //binding.featuredProjectsRecyclerView.itemsCount = featuredProjectsList.size

                categoriesAdapter.setItems(projectsCategoriesList)
            })
    }

    private fun setupProjectsRV() {
        projectsAdapter = HorizontalProjectsAdapter(this)
        binding.myProjectsRecyclerView.apply {
            setHasFixedSize(true)
            LinearSnapHelper().attachToRecyclerView(this)
            adapter = projectsAdapter
        }

        viewModel.getProjects().observe(viewLifecycleOwner, Observer { projects ->
            setAndLoadCurrentProject(projects)
            updateRecyclerview(projects)
        })
    }

    private fun setupFeaturedProjectsRV() {
        featuredProjectsAdapter.setCallback(this@MainMenuFragment)
        /*binding.featuredProjectsRecyclerView.apply {
            setHasFixedSize(true)
            if (itemDecorationCount == 0) {
                addItemDecoration(IndicatorDecoration(requireContext()))
            }
            adapter = featuredProjectsAdapter
            onFlingListener = null
        }.run {
            PagerSnapHelper().attachToRecyclerView(binding.featuredProjectsRecyclerView)
            resumeAutoScroll()
        }*/
    }

    override fun onResume() {
        super.onResume()
        Log.i(STARTUP_TAG, "stage.begin=main-menu-fragment.onResume")
        try {
            (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setTitle(R.string.app_name)

            viewModel.registerNetworkCallback()
            viewModel.setIsLoading(false)
            viewModel.update()
            val projectName = requireActivity().intent.getStringExtra(Constants.EXTRA_PROJECT_NAME)
            if (projectName != null) {
                requireActivity().intent.removeExtra(Constants.EXTRA_PROJECT_NAME)
                loadDownloadedProject(projectName)
            }
            refreshData()
            Log.i(STARTUP_TAG, "stage.complete=main-menu-fragment.onResume")
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "stage.failed=main-menu-fragment.onResume", error)
            throw error
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.unregisterNetworkCallback()
    }

    private fun setAndLoadCurrentProject(myProjects: List<ProjectData>) {
        currentProject = if (myProjects.isNotEmpty()) {
            myProjects[0].name
        } else {
            Utils.getCurrentProjectName(requireContext())
        }
        val projectDir = File(
            DEFAULT_ROOT_DIRECTORY,
            FileMetaDataExtractor.encodeSpecialCharsForFileSystem(currentProject)
        )
        CoroutineScope(Dispatchers.IO).launch {
            // CATROID-1434 Caution
            // running loadProject on main thread may cause ANR due to locking in
            // XstreamSerializer
            loadProject(projectDir, requireContext())
        }
        loadProjectImage()
    }

    private fun loadDownloadedProject(name: String) {
        val projectDir = File(
            DEFAULT_ROOT_DIRECTORY,
            FileMetaDataExtractor.encodeSpecialCharsForFileSystem(name)
        )
        ProjectLoader(projectDir, requireContext())
            .setListener(this)
            .loadProjectAsync()
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
            viewModel.setIsLoading(false)
            ToastUtil.showError(activity, R.string.error_load_project)
        }
    }

    private fun loadProjectImage() {
        val projectDir = File(
            DEFAULT_ROOT_DIRECTORY,
            FileMetaDataExtractor.encodeSpecialCharsForFileSystem(currentProject)
        )
        val loader = ProjectAndSceneScreenshotLoader(CURRENT_THUMBNAIL_SIZE, CURRENT_THUMBNAIL_SIZE)
        loader.loadAndShowScreenshot(
            projectDir.name, loader.getScreenshotSceneName(projectDir), false,
            binding.projectImageView
        )
    }

    private fun updateRecyclerview(myProjects: List<ProjectData>) {
        if (myProjects.size < 2) {
            projectsAdapter.setItems(null)
        } else {
            val projectsCount = myProjects.size.coerceAtMost(MAX_PROJECTS_NUMBER)
            projectsAdapter.setItems(myProjects.subList(1, projectsCount))
        }
    }

    override fun onProjectClick(projectData: ProjectData?) {
        viewModel.setIsLoading(true)
        val projectDir = File(
            DEFAULT_ROOT_DIRECTORY,
            FileMetaDataExtractor
                .encodeSpecialCharsForFileSystem(projectData!!.name)
        )
        ProjectLoader(projectDir, requireContext())
            .setListener(this)
            .loadProjectAsync()
    }

    fun refreshData() {
        viewModel.forceUpdate()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.image_view,
            R.id.projectImageView,
            R.id.editProject -> {
                viewModel.setIsLoading(true)
                val projectDir = File(
                    DEFAULT_ROOT_DIRECTORY,
                    FileMetaDataExtractor.encodeSpecialCharsForFileSystem(currentProject)
                )
                ProjectLoader(projectDir, requireContext())
                    .setListener(this)
                    .loadProjectAsync()
            }

            R.id.newProjectFloatingActionButton -> {
                val dialog = NewProjectDialogFragment()
                dialog.show(parentFragmentManager, NewProjectDialogFragment.TAG)
            }

            /*R.id.uploadProject -> {
                if (Utils.isDefaultProject(projectManager.currentProject, activity)) {
                    binding.root.apply {
                        Snackbar.make(binding.root, R.string.error_upload_default_project, Snackbar.LENGTH_LONG).show()
                    }
                    return
                }

                viewModel.setIsLoading(true)
                val intent = Intent(activity, ProjectUploadActivity::class.java)
                    .putExtra(
                        PROJECT_DIR,
                        File(
                            DEFAULT_ROOT_DIRECTORY,
                            FileMetaDataExtractor.encodeSpecialCharsForFileSystem(
                                Utils.getCurrentProjectName(activity)
                            )
                        )
                    )
                startActivity(intent)
            }*/

            R.id.playProject -> {
                viewModel.setIsLoading(true)
                StageActivity.handlePlayButton(projectManager, activity)
            }

            R.id.myProjectsTextView -> {
                viewModel.setIsLoading(true)
                startActivity(Intent(activity, ProjectListActivity::class.java))
            }

            R.id.featuredProjectsTextView -> {
                viewModel.setIsLoading(true)
                startActivity(Intent(activity, WebViewActivity::class.java))
            }
            R.id.featuredProjectsTextView2 -> {
                viewModel.setIsLoading(true)
                val communityIntent = Intent(activity, CommunityWebViewActivity::class.java)
                startActivity(communityIntent)
            }
        }
    }

    override fun onFeatureProjectClicked(projectUrl: String) {
        openWebView(projectUrl)
    }

    override fun onCategoryTitleClicked(categoryId: String) {
        val categoryUrl = CATEGORY_URL.plus(categoryId)
        openWebView(categoryUrl)
    }

    private fun openWebView(url: String?) {
        val webViewActivityIntent = Intent(activity, WebViewActivity::class.java)
        webViewActivityIntent.putExtra(WebViewActivity.INTENT_PARAMETER_URL, url)
        viewModel.setIsLoading(true)
        startActivity(webViewActivityIntent)
    }

    companion object {
        val TAG: String = MainMenuFragment::class.java.simpleName
        private const val STARTUP_TAG = "NewCatroidStartup"
        private const val CURRENT_THUMBNAIL_SIZE = 500
        private const val MAX_PROJECTS_NUMBER = 10
    }
}
