package com.werewolf.unit.auth

import com.werewolf.auth.AuthService
import com.werewolf.auth.DevAuthController
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Guards the security invariant: the dev-only auth bypass (`POST /api/auth/dev`,
 * an unauthenticated JWT mint) must NEVER be registered when the `prod` profile
 * is active — even if someone also lists `dev` (e.g. `SPRING_PROFILES_ACTIVE=dev,prod`
 * on the VM). Profile-gating is evaluated at bean-registration time, so we assert
 * on the presence of the [DevAuthController] bean under each profile combination
 * rather than booting a full web context.
 */
class DevAuthControllerProfileTest {

    /** Build a bare context with the given active profiles and the controller registered. */
    private fun contextWith(vararg profiles: String): AnnotationConfigApplicationContext {
        val ctx = AnnotationConfigApplicationContext()
        ctx.environment.setActiveProfiles(*profiles)
        // The controller needs an AuthService to instantiate when its @Profile matches.
        ctx.registerBean(AuthService::class.java, Supplier { mock<AuthService>() })
        ctx.register(DevAuthController::class.java)
        ctx.refresh()
        return ctx
    }

    @Test
    fun `dev auth bypass is registered when only dev is active`() {
        contextWith("dev").use { ctx ->
            assertThat(ctx.getBeanNamesForType(DevAuthController::class.java)).isNotEmpty()
        }
    }

    @Test
    fun `dev auth bypass is NOT registered when prod is active alongside dev`() {
        contextWith("dev", "prod").use { ctx ->
            assertThat(ctx.getBeanNamesForType(DevAuthController::class.java)).isEmpty()
        }
    }

    @Test
    fun `dev auth bypass is NOT registered under prod alone`() {
        contextWith("prod").use { ctx ->
            assertThat(ctx.getBeanNamesForType(DevAuthController::class.java)).isEmpty()
        }
    }
}
