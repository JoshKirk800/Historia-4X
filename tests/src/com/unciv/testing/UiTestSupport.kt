package com.unciv.testing

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.view.GameView
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.files.UncivFiles
import com.unciv.ui.audio.MusicController
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.nio.IntBuffer

/**
 * Lets tests running on the [GdxTestRunner] construct real screens and widgets headlessly: [prepare] in `@Before`, then build the screen
 * and look at its actor tree with [actors] and [labelTexts].
 */
object UiTestSupport {
    private var shaderGl: GL20? = null
    private var imagesLoaded = false

    /**
     * Makes the GL mock of the runner able to create the shared sprite batch, loads the images once and gives screens a fresh batch.
     * Needs the [game] to be the one of this test, as `UncivGame.Current` is the one [TestGame] created.
     */
    fun prepare(game: TestGame) {
        Fonts.fontImplementation = FontDesktop()
        UncivGame.Current.settings.alternativeRendering = true
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UncivGame.Current.musicController = MusicController() // screens choose a track for the civilization they show
        // The runner installs a new GL mock per test class, so what was installed once is not necessarily still there
        if (Gdx.gl !== shaderGl) {
            installShaderCapableGl()
            shaderGl = Gdx.gl
        }
        if (!imagesLoaded) {
            ImageGetter.setNewRuleset(game.ruleset)
            imagesLoaded = true
        } else ImageGetter.ruleset = game.ruleset
        BaseScreen.recreateBatch()
    }

    /** SpriteBatch compiles a shader while the screen's stage is created. The runner's GL mock reports failure. */
    private fun installShaderCapableGl() {
        val gl = Mockito.mock(GL20::class.java)
        Mockito.`when`(gl.glCreateShader(Mockito.anyInt())).thenReturn(1)
        Mockito.`when`(gl.glCreateProgram()).thenReturn(1)
        Mockito.`when`(gl.glGenBuffer()).thenReturn(1)
        Mockito.`when`(gl.glGenTexture()).thenReturn(1)
        Mockito.`when`(gl.glGetShaderInfoLog(Mockito.anyInt())).thenReturn("")
        Mockito.`when`(gl.glGetProgramInfoLog(Mockito.anyInt())).thenReturn("")
        val reportStatus = Answer<Any> { invocation ->
            val pname = invocation.getArgument<Int>(1)
            val params = invocation.getArgument<IntBuffer>(2)
            val value = when (pname) {
                GL20.GL_COMPILE_STATUS, GL20.GL_LINK_STATUS -> GL20.GL_TRUE
                else -> 0
            }
            params.put(0, value)
            null
        }
        Mockito.doAnswer(reportStatus).`when`(gl).glGetShaderiv(Mockito.anyInt(), Mockito.anyInt(), Mockito.any())
        Mockito.doAnswer(reportStatus).`when`(gl).glGetProgramiv(Mockito.anyInt(), Mockito.anyInt(), Mockito.any())
        Gdx.gl = gl
        Gdx.gl20 = gl
    }

    /** Every actor of the [stage], breadth first */
    fun actors(stage: Stage): List<Actor> = actors(stage.actors.toList())

    /** [root] and everything below it, breadth first */
    fun actors(root: Actor): List<Actor> = actors(listOf(root))

    private fun actors(roots: List<Actor>): List<Actor> {
        val result = ArrayList<Actor>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val actor = pending.removeFirst()
            result.add(actor)
            if (actor is Group)
                for (child in actor.children) pending.add(child)
        }
        return result
    }

    /** The texts of all labels of the [stage], as shown (translated) */
    fun labelTexts(stage: Stage): List<String> = actors(stage).filterIsInstance<Label>().map { plain(it.text.toString()) }

    /** The texts of all labels at or below [root], as shown (translated) */
    fun labelTexts(root: Actor): List<String> = actors(root).filterIsInstance<Label>().map { plain(it.text.toString()) }

    /** Without the icon glyphs that translation puts in front of names of nations, techs and so on (private use characters) */
    fun plain(text: String): String = text.filter { it.code !in 0xE000..0xF8FF }

    /** Activates [actor] the way a tap does, through its [ActivationListener] */
    fun tap(actor: Actor) {
        actor.listeners.filterIsInstance<ActivationListener>().first()
            .tap(InputEvent().apply { listenerActor = actor }, 0f, 0f, 1, 0)
    }

    /** The first button at or below [root] that shows [text] */
    fun button(root: Actor, text: String): Button =
        actors(root).filterIsInstance<Button>().first { button -> labelTexts(button).any { it == text } }

    /** The first button of the [stage] that shows [text] */
    fun button(stage: Stage, text: String): Button =
        actors(stage).filterIsInstance<Button>().first { button -> labelTexts(button).any { it == text } }

    /** A screen that does nothing, to give popups and stubs a stage */
    fun hostScreen(): BaseScreen = object : BaseScreen() {}

    /**
     * A [WorldScreen] for code that reaches it through `UncivGame.Current.worldScreen` or is handed it (popups, unit action buttons): a mock
     * that shows [viewer]'s view of the game of [game], is the player's turn, and has [host]'s stage. Everything else it is asked for is a mock too. It is installed as the game's world screen.
     */
    fun installWorldScreen(game: TestGame, viewer: Civilization, host: BaseScreen): WorldScreen {
        val view = GameView(game.gameInfo, viewer)
        val world = Mockito.mock(WorldScreen::class.java, Mockito.RETURNS_DEEP_STUBS)
        Mockito.`when`(world.stage).thenReturn(host.stage)
        Mockito.`when`(world.gameInfo).thenReturn(game.gameInfo)
        Mockito.`when`(world.canChangeState).thenReturn(true)
        Mockito.`when`(world.isPlayersTurn).thenReturn(true)
        Mockito.`when`(world.selectedCiv).thenReturn(viewer)
        Mockito.`when`(world.selectedGameView).thenReturn(view)
        UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, world)
        return world
    }

    /**
     * Makes [screen] the top of the game's screen stack above a [hostScreen], so that what its buttons do by `UncivGame.popScreen()`
     * (the picker screens close themselves) has a screen to return to. Gives the host screen back.
     */
    fun putOnScreenStack(screen: BaseScreen): BaseScreen {
        val host = hostScreen()
        @Suppress("UNCHECKED_CAST")
        val stack = UncivGame::class.java.getDeclaredField("screenStack").apply { isAccessible = true }
            .get(UncivGame.Current) as ArrayDeque<BaseScreen>
        stack.clear()
        stack.addLast(host)
        stack.addLast(screen)
        return host
    }
}
