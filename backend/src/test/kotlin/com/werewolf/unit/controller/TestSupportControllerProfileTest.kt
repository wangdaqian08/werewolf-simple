package com.werewolf.unit.controller

import com.werewolf.controller.TestSupportController
import com.werewolf.service.TestSupportService
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Guards the deployment invariant: the destructive test-support cleanup
 * endpoint (DELETE /api/test-support/rooms/{code}) is registered only under
 * the `e2e` or `test` profiles (neither is ever a deployment) — never in dev
 * or prod, and not even when e2e is listed alongside prod. Same
 * bean-registration technique as DevAuthControllerProfileTest.
 */
class TestSupportControllerProfileTest {

    private fun contextWith(vararg profiles: String): AnnotationConfigApplicationContext {
        val ctx = AnnotationConfigApplicationContext()
        ctx.environment.setActiveProfiles(*profiles)
        ctx.registerBean(TestSupportService::class.java, Supplier { mock<TestSupportService>() })
        ctx.register(TestSupportController::class.java)
        ctx.refresh()
        return ctx
    }

    @Test
    fun `registered under the e2e profile`() {
        contextWith("e2e").use { ctx ->
            assertThat(ctx.getBeanNamesForType(TestSupportController::class.java)).isNotEmpty()
        }
    }

    @Test
    fun `registered under the test profile (backend integration suite)`() {
        contextWith("test").use { ctx ->
            assertThat(ctx.getBeanNamesForType(TestSupportController::class.java)).isNotEmpty()
        }
    }

    @Test
    fun `NOT registered under dev`() {
        contextWith("dev").use { ctx ->
            assertThat(ctx.getBeanNamesForType(TestSupportController::class.java)).isEmpty()
        }
    }

    @Test
    fun `NOT registered under prod`() {
        contextWith("prod").use { ctx ->
            assertThat(ctx.getBeanNamesForType(TestSupportController::class.java)).isEmpty()
        }
    }

    @Test
    fun `NOT registered when e2e is listed alongside prod`() {
        contextWith("e2e", "prod").use { ctx ->
            assertThat(ctx.getBeanNamesForType(TestSupportController::class.java)).isEmpty()
        }
    }
}
