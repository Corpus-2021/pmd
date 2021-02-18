/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

@file:Suppress("PropertyName", "unused")

package net.sourceforge.pmd.lang.java.types

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import net.sourceforge.pmd.lang.ast.test.shouldBe
import net.sourceforge.pmd.lang.ast.test.shouldBeA
import net.sourceforge.pmd.lang.java.JavaParsingHelper
import net.sourceforge.pmd.lang.java.ast.*
import net.sourceforge.pmd.lang.java.symbols.internal.asm.AsmSymbolResolver
import net.sourceforge.pmd.lang.java.types.TypeOps.*
import kotlin.String
import kotlin.streams.toList
import kotlin.test.assertTrue

/*
    Note: in parser tests, you can get a log for the inference by calling
    logTypeInference(verbose = true/false)

    Remember to getTypeMirror() / getMethodType() somewhere as the inference
    is done lazily.
 */

val javaParser: JavaParsingHelper = JavaParsingHelper.WITH_PROCESSING

val testTypeSystem: TypeSystem get() = JavaParsingHelper.TEST_TYPE_SYSTEM

// bc the method is package private
val TypeSystem.asmLoader: AsmSymbolResolver get() = this.resolver as AsmSymbolResolver

fun TypeSystem.lub(vararg us: JTypeMirror): JTypeMirror = lub(us.toList())

val TypeSystem.STRING get() = declaration(getClassSymbol(String::class.java)) as JClassType

typealias TypePair = Pair<JTypeMirror, JTypeMirror>


fun JTypeMirror.getMethodsByName(name: String) = streamMethods { it.simpleName == name }.toList()

fun JTypeMirror.shouldBeUnresolvedClass(canonicalName: String) =
        this.shouldBeA<JClassType> {
            it.symbol::getCanonicalName shouldBe canonicalName
        }

infix fun TypeNode.shouldHaveType(jTypeMirror: JTypeMirror) {
    this::getTypeMirror shouldBe jTypeMirror
}

// pairs of type param name to value
fun JMethodSig.subst(vararg mapping: Pair<String, JTypeMirror>): JMethodSig {
    var subst = Substitution.EMPTY
    for ((name, t) in mapping) {
        val tvar = this.typeParameters.firstOrNull { it.name == name }
        subst = subst.plus(tvar, t)
    }
    return this.subst(subst)
}

infix fun JMethodSig?.shouldBeSomeInstantiationOf(m: JMethodSig): JMethodSig {
    if (this == null)
        fail("Got null, expected some instantiation of $m")

    this::getSymbol shouldBe m.symbol
    this::getArity shouldBe m.arity
    this::getName shouldBe m.name
    this::getTypeParameters shouldBe m.typeParameters

    return this
}

fun JMethodSig?.shouldMatchMethod(
        named: String,
        declaredIn: JTypeMirror? = null,
        withFormals: List<JTypeMirror>? = null,
        returning: JTypeMirror? = null
): JMethodSig {
    if (this == null)
        fail("Expected non-null result")

    withClue(this) {
        this::getName shouldBe named

        if (declaredIn != null)
            this::getDeclaringType shouldBe declaredIn

        if (withFormals != null)
            this::getFormalParameters shouldBe withFormals

        if (returning != null)
            this::getReturnType shouldBe returning

    }
    return this
}

fun InvocationNode.shouldUseUncheckedConversion() {
    overloadSelectionInfo::needsUncheckedConversion shouldBe true
}

fun JTypeMirror.shouldBeCaptureOf(wild: JWildcardType) =
        this.shouldBeA<JTypeVar> {
            it.isCaptured shouldBe true
            if (wild.isLowerBound)
                it.lowerBound shouldBe wild.asLowerBound()
            else
                it.upperBound shouldBe wild.asUpperBound()
        }


/**
 * assertSubtypeOrdering(a, b, c) asserts that a >: b >: c
 * In other words, the supertypes are on the left, subtypes on the right
 */
fun assertSubtypeOrdering(vararg ts: JTypeMirror) {
    for ((a, b) in ts.zip(ts.asList().drop(1))) {
        assertSubtype(b, a) { bySubtyping() }
    }
}

fun JClassType.parameterize(m1: JTypeMirror, vararg mirror: JTypeMirror): JClassType = withTypeArguments(listOf(m1, *mirror))

fun assertSubtype(t: JTypeMirror, s: JTypeMirror, capture: Boolean = true, passes: Convertibility.() -> Boolean) {
    val res = isConvertible(t, s, capture)
    assertTrue("$t \n\t\t<: $s") {
        res.passes()
    }
}

infix fun JTypeMirror.shouldSubtypeNoCapture(s: JTypeMirror) {
    assertSubtype(this, s, false) { bySubtyping() }
}

infix fun JTypeMirror.shouldNotSubtypeNoCapture(s: JTypeMirror) {
    assertSubtype(this, s, false) { never() }
}

infix fun JTypeMirror.shouldBeSubtypeOf(other: JTypeMirror) {
    assertSubtype(this, other)  { bySubtyping() }
    // assertSubtype(other, this, SubtypeResult.definitely(this == other))
}

infix fun JTypeMirror.shouldNotBeSubtypeOf(other: JTypeMirror) {
    assertSubtype(this, other)  { never() }
}

infix fun JTypeMirror.shouldBeUncheckedSubtypeOf(other: JTypeMirror) {
    assertSubtype(this, other)  { withUncheckedWarning() }
}

infix fun JTypeMirror.shouldBeUnrelatedTo(other: JTypeMirror) {
    if (this == other) return
    assertSubtype(this, other) { never() }
    assertSubtype(other, this) { never() }
}

/** A type that binds to a capture variable for the given wildcard. */
fun captureMatcher(wild: JWildcardType): JTypeVar =
        CaptureMatcher(wild)


fun JTypeMirror.shouldBePrimitive(kind: JPrimitiveType.PrimitiveTypeKind) {
    this shouldBe typeSystem.getPrimitive(kind)
}

fun canIntersect(t: JTypeMirror, s: JTypeMirror, vararg others: JTypeMirror): Boolean {
    val comps = listOf(t, s, *others)
    return comps.filter { it.isExlusiveIntersectionBound }.size <= 1
            && comps.none { it.isPrimitive || it.isGenericTypeDeclaration }
}

/** If so, there can only be one in a well formed intersection. */
val JTypeMirror.isExlusiveIntersectionBound
    get() = this is JArrayType
            || this is JClassType && this.symbol.isClass
            || this is JTypeVar