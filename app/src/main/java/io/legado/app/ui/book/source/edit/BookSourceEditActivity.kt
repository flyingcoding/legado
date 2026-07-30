package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.databinding.ActivityBookSourceEditBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollLinearLayoutManager
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding

class BookSourceEditActivity :
    VMBaseActivity<ActivityBookSourceEditBinding, BookSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityBookSourceEditBinding::inflate)
    override val viewModel by viewModels<BookSourceEditViewModel>()

    private val adapter by lazy { BookSourceEditAdapter() }
    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val searchEntities: ArrayList<EditEntity> = ArrayList()
    private val exploreEntities: ArrayList<EditEntity> = ArrayList()
    private val infoEntities: ArrayList<EditEntity> = ArrayList()
    private val tocEntities: ArrayList<EditEntity> = ArrayList()
    private val contentEntities: ArrayList<EditEntity> = ArrayList()
    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private var displayedReviewRule: ReviewRule? = null
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        viewModel.importSource(it) { source ->
            upSourceView(source)
        }
    }
    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            upSourceView(viewModel.bookSource)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("ruleHelp")
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !getSource().loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_auto_complete)?.isChecked = viewModel.autoComplete
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> saveSource {
                setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
                finish()
            }

            R.id.menu_debug_source -> saveSource { source ->
                startActivity<BookSourceDebugActivity> {
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_clear_cookie -> viewModel.clearCookie(getSource().bookSourceUrl)
            R.id.menu_auto_complete -> viewModel.autoComplete = !viewModel.autoComplete
            R.id.menu_copy_source -> withValidSource { sendToClip(GSON.toJson(it)) }
            R.id.menu_paste_source -> viewModel.pasteSource { upSourceView(it) }
            R.id.menu_qr_code_camera -> qrCodeResult.launch()
            R.id.menu_share_str -> withValidSource { share(GSON.toJson(it)) }
            R.id.menu_share_qr -> withValidSource {
                shareWithQr(
                    GSON.toJson(it),
                    getString(R.string.share_book_source),
                    ErrorCorrectionLevel.L
                )
            }

            R.id.menu_help -> showHelp("ruleHelp")
            R.id.menu_login -> saveSource { source ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_search -> saveSource { source ->
                startActivity<SearchActivity> {
                    putExtra("searchScope", SearchScope(source).toString())
                }
            }

        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_base)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_search)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_find)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_info)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_toc)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_content)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_review)
        })
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.tabLayout.setBackgroundColor(backgroundColor)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabSelected(tab: TabLayout.Tab?) {
                setEditEntities(tab?.position)
            }
        })
        binding.recyclerView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val navigationBarHeight = windowInsets.navigationBarHeight
            val imeHeight = windowInsets.imeHeight
            view.bottomPadding = if (imeHeight == 0) navigationBarHeight else 0
            softKeyboardTool.initialPadding = imeHeight
            windowInsets
        }
    }

    override fun finish() {
        val source = getSource()
        if (!source.equal(viewModel.bookSource ?: BookSource())) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
        } else {
            super.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun setEditEntities(tabPosition: Int?) {
        adapter.editEntities = when (tabPosition) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
            REVIEW_TAB_POSITION -> reviewEntities
            else -> sourceEntities
        }
        binding.recyclerView.scrollToPosition(0)
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        displayedReviewRule = bs.ruleReview?.copy()
        bs.let {
            binding.cbIsEnable.isChecked = it.enabled
            binding.cbIsEnableExplore.isChecked = it.enabledExplore
            binding.cbIsEnableCookie.isChecked = it.enabledCookieJar ?: false
            binding.spType.setSelection(
                when (it.bookSourceType) {
                    BookSourceType.file -> 3
                    BookSourceType.image -> 2
                    BookSourceType.audio -> 1
                    else -> 0
                }
            )
        }
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, R.string.source_url))
            add(EditEntity("bookSourceName", bs.bookSourceName, R.string.source_name))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, R.string.source_group))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, R.string.comment))
            add(EditEntity("loginUrl", bs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", bs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, R.string.book_url_pattern))
            add(EditEntity("header", bs.header, R.string.source_http_header))
            add(EditEntity("variableComment", bs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", bs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("jsLib", bs.jsLib, "jsLib"))
        }
        // 搜索
        val sr = bs.getSearchRule()
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, R.string.r_search_url))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, R.string.check_key_word))
            add(EditEntity("bookList", sr.bookList, R.string.r_book_list))
            add(EditEntity("name", sr.name, R.string.r_book_name))
            add(EditEntity("author", sr.author, R.string.r_author))
            add(EditEntity("kind", sr.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", sr.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", sr.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", sr.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", sr.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", sr.bookUrl, R.string.r_book_url))
        }
        // 发现
        val er = bs.getExploreRule()
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, R.string.r_find_url))
            add(EditEntity("bookList", er.bookList, R.string.r_book_list))
            add(EditEntity("name", er.name, R.string.r_book_name))
            add(EditEntity("author", er.author, R.string.r_author))
            add(EditEntity("kind", er.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", er.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", er.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", er.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", er.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", er.bookUrl, R.string.r_book_url))
        }
        // 详情页
        val ir = bs.getBookInfoRule()
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, R.string.rule_book_info_init))
            add(EditEntity("name", ir.name, R.string.r_book_name))
            add(EditEntity("author", ir.author, R.string.r_author))
            add(EditEntity("kind", ir.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", ir.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", ir.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", ir.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", ir.coverUrl, R.string.rule_cover_url))
            add(EditEntity("tocUrl", ir.tocUrl, R.string.rule_toc_url))
            add(EditEntity("canReName", ir.canReName, R.string.rule_can_re_name))
            add(EditEntity("downloadUrls", ir.downloadUrls, R.string.download_url_rule))
        }
        // 目录页
        val tr = bs.getTocRule()
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, R.string.pre_update_js))
            add(EditEntity("chapterList", tr.chapterList, R.string.rule_chapter_list))
            add(EditEntity("chapterName", tr.chapterName, R.string.rule_chapter_name))
            add(EditEntity("chapterUrl", tr.chapterUrl, R.string.rule_chapter_url))
            add(EditEntity("formatJs", tr.formatJs, R.string.format_js_rule))
            add(EditEntity("isVolume", tr.isVolume, R.string.rule_is_volume))
            add(EditEntity("updateTime", tr.updateTime, R.string.rule_update_time))
            add(EditEntity("isVip", tr.isVip, R.string.rule_is_vip))
            add(EditEntity("isPay", tr.isPay, R.string.rule_is_pay))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, R.string.rule_next_toc_url))
        }
        // 正文页
        val cr = bs.getContentRule()
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, R.string.rule_book_content))
            add(EditEntity("title", cr.title, R.string.rule_chapter_name))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, R.string.rule_next_content))
            add(EditEntity("webJs", cr.webJs, R.string.rule_web_js))
            add(EditEntity("sourceRegex", cr.sourceRegex, R.string.rule_source_regex))
            add(EditEntity("replaceRegex", cr.replaceRegex, R.string.rule_replace_regex))
            add(EditEntity("imageStyle", cr.imageStyle, R.string.rule_image_style))
            add(EditEntity("imageDecode", cr.imageDecode, R.string.rule_image_decode))
            add(EditEntity("payAction", cr.payAction, R.string.rule_pay_action))
        }
        // 段评
        val rr = bs.getReviewRule()
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("contractVersion", rr.contractVersion, R.string.rule_review_contract))
            add(EditEntity("reviewIndexUrl", rr.reviewIndexUrl, R.string.rule_review_index_url))
            add(EditEntity("reviewUrl", rr.reviewUrl, R.string.rule_review_url))
            add(EditEntity("reviewQuoteUrl", rr.reviewQuoteUrl, R.string.rule_review_quote_url))
            add(EditEntity("paragraphListRule", rr.paragraphListRule, R.string.rule_paragraph_list))
            add(EditEntity("paragraphIdRule", rr.paragraphIdRule, R.string.rule_paragraph_id))
            add(
                EditEntity(
                    "paragraphCountRule",
                    rr.paragraphCountRule,
                    R.string.rule_paragraph_count
                )
            )
            add(EditEntity("commentListRule", rr.commentListRule, R.string.rule_comment_list))
            add(EditEntity("commentIdRule", rr.commentIdRule, R.string.rule_comment_id))
            add(EditEntity("userIdRule", rr.userIdRule, R.string.rule_comment_user_id))
            add(EditEntity("userNameRule", rr.userNameRule, R.string.rule_comment_user_name))
            add(EditEntity("avatarRule", rr.avatarRule, R.string.rule_comment_avatar))
            add(EditEntity("contentRule", rr.contentRule, R.string.rule_comment_content))
            add(EditEntity("postTimeRule", rr.postTimeRule, R.string.rule_comment_post_time))
            add(
                EditEntity(
                    "voteUpCountRule",
                    rr.voteUpCountRule,
                    R.string.rule_comment_vote_up_count
                )
            )
            add(EditEntity("quoteCountRule", rr.quoteCountRule, R.string.rule_comment_quote_count))
            add(EditEntity("hasMoreRule", rr.hasMoreRule, R.string.rule_review_has_more))
            add(EditEntity("nextCursorRule", rr.nextCursorRule, R.string.rule_review_next_cursor))
            add(EditEntity("quoteListRule", rr.quoteListRule, R.string.rule_quote_list))
            add(EditEntity("quoteIdRule", rr.quoteIdRule, R.string.rule_quote_id))
            add(EditEntity("quoteParentIdRule", rr.quoteParentIdRule, R.string.rule_quote_parent_id))
            add(EditEntity("quoteUserIdRule", rr.quoteUserIdRule, R.string.rule_quote_user_id))
            add(
                EditEntity(
                    "quoteUserNameRule",
                    rr.quoteUserNameRule,
                    R.string.rule_quote_user_name
                )
            )
            add(EditEntity("quoteAvatarRule", rr.quoteAvatarRule, R.string.rule_quote_avatar))
            add(EditEntity("quoteContentRule", rr.quoteContentRule, R.string.rule_quote_content))
            add(
                EditEntity(
                    "quotePostTimeRule",
                    rr.quotePostTimeRule,
                    R.string.rule_quote_post_time
                )
            )
            add(
                EditEntity(
                    "quoteVoteUpCountRule",
                    rr.quoteVoteUpCountRule,
                    R.string.rule_quote_vote_up_count
                )
            )
            add(EditEntity("quoteChildrenRule", rr.quoteChildrenRule, R.string.rule_quote_children))
        }
        binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
        setEditEntities(0)
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        source.enabled = binding.cbIsEnable.isChecked
        source.enabledExplore = binding.cbIsEnableExplore.isChecked
        source.enabledCookieJar = binding.cbIsEnableCookie.isChecked
        source.bookSourceType = when (binding.spType.selectedItemPosition) {
            3 -> BookSourceType.file
            2 -> BookSourceType.image
            1 -> BookSourceType.audio
            else -> BookSourceType.default
        }
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.value ?: ""
                "bookSourceName" -> source.bookSourceName = it.value ?: ""
                "bookSourceGroup" -> source.bookSourceGroup = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "bookUrlPattern" -> source.bookUrlPattern = it.value
                "header" -> source.header = it.value
                "bookSourceComment" -> source.bookSourceComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "variableComment" -> source.variableComment = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        searchEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.value
                "checkKeyWord" -> searchRule.checkKeyWord = it.value
                "bookList" -> searchRule.bookList = it.value
                "name" -> searchRule.name =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "author" -> searchRule.author =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "kind" -> searchRule.kind =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "intro" -> searchRule.intro =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

//                "updateTime" -> searchRule.updateTime =
//                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "wordCount" -> searchRule.wordCount =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "lastChapter" -> searchRule.lastChapter =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "coverUrl" -> searchRule.coverUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 3)

                "bookUrl" -> searchRule.bookUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 2)
            }
        }
        exploreEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.value
                "bookList" -> exploreRule.bookList = it.value
                "name" -> exploreRule.name =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "author" -> exploreRule.author =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "kind" -> exploreRule.kind =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "intro" -> exploreRule.intro =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

//                "updateTime" -> exploreRule.updateTime =
//                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "wordCount" -> exploreRule.wordCount =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "lastChapter" -> exploreRule.lastChapter =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "coverUrl" -> exploreRule.coverUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 3)

                "bookUrl" -> exploreRule.bookUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 2)
            }
        }
        infoEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "init" -> bookInfoRule.init = it.value
                "name" -> bookInfoRule.name = viewModel.ruleComplete(it.value, bookInfoRule.init)
                "author" -> bookInfoRule.author =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "kind" -> bookInfoRule.kind =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "intro" -> bookInfoRule.intro =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

//                "updateTime" -> bookInfoRule.updateTime =
//                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "wordCount" -> bookInfoRule.wordCount =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "lastChapter" -> bookInfoRule.lastChapter =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "coverUrl" -> bookInfoRule.coverUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 3)

                "tocUrl" -> bookInfoRule.tocUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 2)

                "canReName" -> bookInfoRule.canReName = it.value
                "downloadUrls" -> bookInfoRule.downloadUrls =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)
            }
        }
        tocEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.value
                "chapterList" -> tocRule.chapterList = it.value
                "chapterName" -> tocRule.chapterName =
                    viewModel.ruleComplete(it.value, tocRule.chapterList)

                "chapterUrl" -> tocRule.chapterUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)

                "formatJs" -> tocRule.formatJs = it.value
                "isVolume" -> tocRule.isVolume = it.value
                "updateTime" -> tocRule.updateTime = it.value
                "isVip" -> tocRule.isVip = it.value
                "isPay" -> tocRule.isPay = it.value
                "nextTocUrl" -> tocRule.nextTocUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)
            }
        }
        contentEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "content" -> contentRule.content = viewModel.ruleComplete(it.value)
                "title" -> contentRule.title = viewModel.ruleComplete(it.value)
                "nextContentUrl" -> contentRule.nextContentUrl =
                    viewModel.ruleComplete(it.value, type = 2)

                "webJs" -> contentRule.webJs = it.value
                "sourceRegex" -> contentRule.sourceRegex = it.value
                "replaceRegex" -> contentRule.replaceRegex = it.value
                "imageStyle" -> contentRule.imageStyle = it.value
                "imageDecode" -> contentRule.imageDecode = it.value
                "payAction" -> contentRule.payAction = it.value
            }
        }
        source.ruleSearch = searchRule
        source.ruleExplore = exploreRule
        source.ruleBookInfo = bookInfoRule
        source.ruleToc = tocRule
        source.ruleContent = contentRule
        source.ruleReview = reviewEntities.associate { it.key to it.value }.toReviewRuleOrNull()
        return source
    }

    /** 校验只读段评合同后保存书源，并在失败时定位到段评规则页。 */
    private fun saveSource(success: ((BookSource) -> Unit)? = null) {
        withValidSource { source ->
            viewModel.save(source) { savedSource ->
                displayedReviewRule = savedSource.ruleReview?.copy()
                success?.invoke(savedSource)
            }
        }
    }

    /** 校验当前段评编辑结果，阻止新建或改动后的不完整规则被保存或分享。 */
    private fun withValidSource(action: (BookSource) -> Unit) {
        val source = getSource()
        if (!source.ruleReview.isValidEditFrom(displayedReviewRule)) {
            binding.tabLayout.getTabAt(REVIEW_TAB_POSITION)?.select()
            toastOnUi(R.string.rule_review_invalid)
            return
        }
        action(source)
    }

    private fun alertGroups() {
        lifecycleScope.launch {
            val groups = withContext(IO) {
                appDb.bookSourceDao.allGroups()
            }
            selector(groups) { _, s, _ ->
                sendText(s)
            }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        val helpActions = arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("书源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
        )
        val view = window.decorView.findFocus()
        if (view is EditText) {
            when (view.getTag(R.id.tag)) {
                "bookSourceGroup" -> {
                    helpActions.add(
                        SelectItem("插入分组", "addGroup")
                    )
                }

                else -> {
                    helpActions.add(
                        SelectItem("选择文件", "selectFile")
                    )
                }
            }
        }
        return helpActions
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "addGroup" -> alertGroups()
            "urlOption" -> UrlOptionDialog(this) { sendText(it) }.show()
            "ruleHelp" -> showHelp("ruleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
    }

    override fun sendText(text: String) {
        if (text.isBlank()) return
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val start = view.selectionStart
            val end = view.selectionEnd
            val edit = view.editableText//获取EditText的文字
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else if (start > end) {
                edit.replace(end, start, text)
            } else {
                edit.replace(start, end, text)//光标所在位置插入文字
            }
        }
    }

    private fun setSourceVariable() {
        saveSource { source ->
            lifecycleScope.launch {
                val comment =
                    source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
                val variable = withContext(IO) { source.getVariable() }
                showDialogFragment(
                    VariableDialog(
                        getString(R.string.set_source_variable),
                        source.getKey(),
                        variable,
                        comment
                    )
                )
            }
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.bookSource?.setVariable(variable)
    }

    private companion object {

        const val REVIEW_TAB_POSITION = 6
    }

}
