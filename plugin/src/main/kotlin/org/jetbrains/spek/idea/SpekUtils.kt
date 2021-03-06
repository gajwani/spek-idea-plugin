package org.jetbrains.spek.idea

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiUtil
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.util.findAnnotation
import org.jetbrains.kotlin.lexer.KtToken
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtStubbedPsiUtil
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.spek.tooling.Path
import org.jetbrains.spek.tooling.PathType

/**
 * @author Ranie Jade Ramiso
 */
object SpekUtils {
    private val GROUP_FN = arrayOf(
        "describe",
        "context",
        "given",
        "on"
    )

    private val TEST_FN = arrayOf(
        "it"
    )

    fun isSpec(cls: KtLightClass): Boolean {
        val facade = JavaPsiFacade.getInstance(cls.project)
        val scope = cls.resolveScope

        val spec = facade.findClass("org.jetbrains.spek.api.Spek", scope)

        if (spec == null) {
            return false
        }

        return !PsiUtil.isAbstractClass(cls)
            && cls.isInheritor(spec, true)
    }

    fun isSpecBlock(callExpression: KtCallExpression): Boolean {
        val parameters = callExpression.valueArguments
        val lambda = callExpression.lambdaArguments.firstOrNull()
        val calleeExpression = callExpression.calleeExpression!! as KtNameReferenceExpression
        val resolved = calleeExpression.mainReference.resolve()

        if (resolved != null && resolved is KtNamedFunction && isContainedWithinLambda(callExpression)) {
            if (lambda != null && parameters.size == 2 && isDslExtension(resolved)) {
                val desc = parameters.first().children.firstOrNull()
                if (desc != null && desc is KtStringTemplateExpression) {
                    return isTest(resolved) || isGroup(resolved)
                }
            }
        }
        return false
    }

    fun isSpec(cls: KtClassOrObject): Boolean {
        val lcls = cls.toLightClass()
        if (lcls != null) {
            return isSpec(lcls)
        }
        return false
    }

    fun isJUnit4(cls: KtClassOrObject): Boolean {
        val fqName = FqName("org.junit.runner.RunWith")
        return cls.findAnnotation(fqName) != null
    }

    fun isGroup(function: KtNamedFunction): Boolean {
        return GROUP_FN.contains(function.name)
    }

    fun isTest(function: KtNamedFunction): Boolean {
        return TEST_FN.contains(function.name)
    }

    fun isContainedInSpec(callExpression: KtCallExpression): Boolean {
        val container = KtStubbedPsiUtil.getContainingDeclaration(callExpression, KtClassOrObject::class.java)
        if (container != null) {
            return isSpec(container)
        }
        return false
    }

    fun getContainingSpecClass(callExpression: KtCallExpression): KtLightClass? {
        val container = KtStubbedPsiUtil.getContainingDeclaration(callExpression, KtClassOrObject::class.java)
        if (container != null && isSpec(container)) {
            return container.toLightClass()
        }
        return null
    }

    fun extractPath(callExpression: KtCallExpression, next: Path? = null): Path {
        val lambda = getLambaExpression(callExpression)
        val parent = lambda.parent
        val calleeExpression = callExpression.calleeExpression as KtNameReferenceExpression
        val parameters = callExpression.valueArguments
        val function = calleeExpression.mainReference.resolve() as KtNamedFunction
        val stringExpression = parameters.first().children.firstOrNull() as KtStringTemplateExpression
        val description = stringExpression.text.removeSurrounding("\"")

        val fullDesc = "${function.name!!} $description"

        val type = if (isTest(function)) {
            PathType.TEST
        } else {
            PathType.GROUP
        }

        val path = Path(type, fullDesc, next)

        return if (parent is KtLambdaArgument) {
            val parentCallExpression = parent.parent as KtCallExpression

            extractPath(parentCallExpression, path)
        } else {
            val container = getContainingSpecClass(callExpression)
            Path(PathType.SPEC, container!!.qualifiedName!!, path)
        }
    }

    /**
     * Retrieve the lambda expression containing the call expression
     */
    fun getLambaExpression(callExpression: KtCallExpression): KtLambdaExpression {
        // CallExpression -> Block -> FunctionLiteral -> LambdaExpression
        return callExpression.parent.parent.parent as KtLambdaExpression
    }

    fun isContainedWithinLambda(callExpression: KtCallExpression) = callExpression.parent.parent.parent is KtLambdaExpression

    fun isDslExtension(function: KtNamedFunction): Boolean {
        val receiverTypeReference = function.receiverTypeReference
        if (receiverTypeReference != null) {
            val referencedName = (receiverTypeReference.typeElement as KtUserType).referencedName
            return referencedName in arrayOf("Dsl", "Spec", "SpecBody", "TestBody", "ActionBody", "TestContainer")
        }
        return false
    }

    fun isIdentifier(element: PsiElement): Boolean {
        val node = element.node
        if (node != null) {
            val elementType = node.elementType
            if (elementType is KtToken) {
                return elementType.toString() == "IDENTIFIER"
            }
        }
        return false
    }
}
