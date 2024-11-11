package lang.mu

import kotlin.test.Test

class FOSolverSpec {
    sealed interface Expr {
        data class Var(val name: String) : Expr
        data class App(val f: Expr, val args: List<Expr>) : Expr
        data class Lam(val name: String, val body: Expr) : Expr
    }

    @Test fun `test`() {
        // TODO
    }
}
