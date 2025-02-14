package io.github.xpler.core.entity

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import io.github.xpler.core.XplerHelper
import io.github.xpler.core.XplerLog
import io.github.xpler.core.proxy.LoadParam
import io.github.xpler.core.hook.ConstructorHookImpl
import io.github.xpler.core.hook.MethodHookImpl
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.utils.XplerUtils
import java.lang.reflect.Method

/// 使用案例, 详见: https://github.com/GangJust/xpler/blob/master/readme.md

/**
 * HookEntity 实体类，提供对某个Hook目标类的便捷操作
 */
abstract class HookEntity(
    protected val param: LoadParam = XplerHelper.lparam,
) {
    private lateinit var mTargetClass: Class<*>
    private val targetMethods = mutableSetOf<Method>()

    private var helper: XplerHelper? = null
    private val mineMethods = mutableSetOf<Method>()

    init {
        runCatching {
            mTargetClass = this.setTargetClass()
            if (targetClass == NoneHook::class.java)
                return@runCatching

            if (targetClass == Nothing::class.java)
                return@runCatching

            if (targetClass != EmptyHook::class.java) {
                if (targetClass == Any::class.java)
                    throw ClassFormatError("Please override the `setTargetClass()` to specify the hook target class!")

                helper = XplerHelper.hookClass(targetClass)

                getMineAllMethods()
                getHookTargetAllMethods()

                invOnBefore()
                invOnAfter()
                invOnReplace()

                invOnConstructorBefore()
                invOnConstructorAfter()
                invOnConstructorReplace()

                hookTargetAllMethod()
                hookTargetAllConstructor()
            }

            this.onInit()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 子类手动设置目标类
     */
    abstract fun setTargetClass(): Class<*>

    /**
     * 获取当前目标类
     */
    protected val targetClass get() = mTargetClass

    /**
     * 相当于每个Hook逻辑类的入口方法
     */
    open fun onInit() {}

    /**
     * 查找某类
     */
    open fun findClass(className: String, classLoader: ClassLoader? = null): Class<*> {
        return try {
            XposedHelpers.findClass(simpleName(className), classLoader ?: param.classLoader)
        } catch (e: Exception) {
            XplerLog.e(e)
            NoneHook::class.java
        }
    }

    /**
     * 字节码签名转换：Landroid/view/View; -> android.view.View
     */
    protected fun simpleName(name: String): String {
        return XplerUtils.simpleName(name)
    }

    /**
     * 获取子类的所有方法
     */
    private fun getMineAllMethods() {
        mineMethods.addAll(this::class.java.declaredMethods)
    }

    /**
     * 获取Hook目标类中的所有方法
     */
    private fun getHookTargetAllMethods() {
        targetMethods.addAll(targetClass.declaredMethods)
    }

    /**
     * 过滤目标方法。
     *
     * @param names
     * @param paramTypes
     * @param returnType
     * @return List<Method>
     */
    private fun filterHookMethods(
        value: Method,
        names: Array<out String>,
        paramTypes: Array<Class<*>?>,
        returnType: String,
    ): List<Method> {
        // 目标方法名、参数列表、返回类型同时为空
        if (names.isEmpty() && paramTypes.isEmpty() && returnType.isEmpty()) {
            return emptyList()
        }

        var sequence = targetMethods.asSequence()

        // 具有方法名
        if (names.isNotEmpty()) {
            sequence = sequence.filter { names.contains(it.name) }
        }

        // 具有参数列表
        if (paramTypes.isNotEmpty()) {
            sequence = sequence.filter { XplerUtils.compareParamTypes(it, paramTypes) }
        }

        // 具有返回值
        if (returnType.isNotEmpty()) {
            sequence = sequence.filter { simpleName(returnType) == it.returnType.name }
        }

        return sequence.toList().also { item ->
            if (item.isEmpty()) {
                val errMsg = StringBuilder()
                errMsg.append("\n╭───────────────────────────────\n")
                errMsg.append("├─${NoSuchMethodException::class.java.name}: No method in the target class meets the following conditions!\n")
                errMsg.append("├─entity: ${this.javaClass.name}#${value.name}\n")
                errMsg.append("├─target: ${targetClass.name}\n")
                errMsg.append("├─names: ${names.joinToString(", ")}\n")
                errMsg.append("├─params: ${paramTypes.joinToString(", ") { it?.name ?: "null" }}\n")
                errMsg.append("├─return: $returnType")
                errMsg.append("\n╰───────────────────────────────\n")
                XplerLog.e(errMsg.toString())
            }
        }
    }

    /**
     * 查找子类方法[mineMethods]中被 [OnBefore] 标注的所有方法, 并将其Hook
     */
    private fun invOnBefore() {
        val methodMap = getAnnotationMethod(OnBefore::class.java)
        for ((key, value) in methodMap) {
            if (value.getAnnotation(OnReplace::class.java) != null) continue
            value.isAccessible = true

            val names = key.key.name
            val paramTypes = getTargetMethodParamTypes(value)
            val returnType = value.getAnnotation(ReturnType::class.java)?.name?.trim() ?: ""

            val methods = filterHookMethods(value, names, paramTypes, returnType)

            methods.forEach {
                MethodHookImpl(it).apply {
                    onBefore {
                        val invArgs = if (paramTypes.isEmpty()) {
                            arrayOf(this) // 如果只提供了方法名、返回值类型
                        } else {
                            arrayOf(this, *args)
                        }
                        value.invoke(this@HookEntity, *invArgs)
                    }
                    if (value.getAnnotation(HookOnce::class.java) != null) {
                        onUnhook { unhook() }
                    }
                }.startHook()
            }
        }
    }

    /**
     * 查找子类方法[mineMethods]中被 [OnAfter] 标注的所有方法, 并将其Hook
     */
    private fun invOnAfter() {
        val methodMap = getAnnotationMethod(OnAfter::class.java)
        for ((key, value) in methodMap) {
            if (value.getAnnotation(OnReplace::class.java) != null) continue
            value.isAccessible = true

            val names = key.key.name
            val paramTypes = getTargetMethodParamTypes(value)
            val returnType = value.getAnnotation(ReturnType::class.java)?.name?.trim() ?: ""

            val methods = filterHookMethods(value, names, paramTypes, returnType)

            methods.forEach {
                MethodHookImpl(it).apply {
                    onAfter {
                        val invArgs = if (paramTypes.isEmpty()) {
                            arrayOf(this) // 如果只提供了方法名、返回值类型
                        } else {
                            arrayOf(this, *args)
                        }
                        value.invoke(this@HookEntity, *invArgs)
                    }
                    if (value.getAnnotation(HookOnce::class.java) != null) {
                        onUnhook { unhook() }
                    }
                }.startHook()
            }
        }
    }

    /**
     * 查找子类方法[mineMethods]中被 [OnReplace] 标注的所有方法,并将其Hook
     * 值得注意的是, 某个方法一旦标注了 [OnReplace] 如果该方法又同时
     * 被[@OnBefore]或[@OnAfter]标注, 该方法跳过它们, 只对 [@OnReplace] 生效
     */
    private fun invOnReplace() {
        val methodMap = getAnnotationMethod(OnReplace::class.java)
        for ((key, value) in methodMap) {
            value.isAccessible = true

            val names = key.key.name
            val paramTypes = getTargetMethodParamTypes(value)
            val returnType = value.getAnnotation(ReturnType::class.java)?.name?.trim() ?: ""

            val methods = filterHookMethods(value, names, paramTypes, returnType)

            methods.forEach {
                MethodHookImpl(it).apply {
                    onReplace {
                        val invArgs = if (paramTypes.isEmpty()) {
                            arrayOf(this) // 如果只提供了方法名、返回值类型
                        } else {
                            arrayOf(this, *args)
                        }
                        value.invoke(this@HookEntity, *invArgs) ?: Unit
                    }
                    if (value.getAnnotation(HookOnce::class.java) != null) {
                        onUnhook { unhook() }
                    }
                }.startHook()
            }
        }
    }

    /**
     * 查找子类方法[mineMethods]中被 [OnConstructorBefore] 标注的所有方法, 并将其Hook
     */
    private fun invOnConstructorBefore() {
        val methodMap = getAnnotationMethod(OnConstructorBefore::class.java)
        for ((_, value) in methodMap) {
            if (value.getAnnotation(OnConstructorReplace::class.java) != null) continue
            value.isAccessible = true

            val paramTypes = getTargetMethodParamTypes(value)
            val normalParamTypes = paramTypes.map { it ?: Any::class.java }.toTypedArray()
            ConstructorHookImpl(targetClass, *normalParamTypes)
                .apply {
                    onBefore {
                        val invArgs = arrayOf(this, *args)
                        value.invoke(this@HookEntity, *invArgs)
                    }
                    if (value.getAnnotation(HookOnce::class.java) != null) {
                        onUnhook { unhook() }
                    }
                }.startHook()
        }
    }

    /**
     * 查找子类方法[mineMethods]中被 [OnConstructorAfter] 标注的所有方法, 并将其Hook
     */
    private fun invOnConstructorAfter() {
        val methodMap = getAnnotationMethod(OnConstructorAfter::class.java)
        for ((_, value) in methodMap) {
            if (value.getAnnotation(OnConstructorReplace::class.java) != null) continue
            value.isAccessible = true

            val paramTypes = getTargetMethodParamTypes(value)
            val normalParamTypes = paramTypes.map { it ?: Any::class.java }.toTypedArray()
            ConstructorHookImpl(targetClass, *normalParamTypes)
                .apply {
                    onAfter {
                        val invArgs = arrayOf(this, *args)
                        value.invoke(this@HookEntity, *invArgs)
                    }
                    if (value.getAnnotation(HookOnce::class.java) != null) {
                        onUnhook { unhook() }
                    }
                }.startHook()
        }
    }

    /**
     * 查找子类方法[mineMethods]中被 [OnConstructorReplace] 标注的所有方法, 并将其Hook
     */
    private fun invOnConstructorReplace() {
        val methodMap = getAnnotationMethod(OnConstructorReplace::class.java)
        for ((_, value) in methodMap) {
            value.isAccessible = true
            val paramTypes = getTargetMethodParamTypes(value)
            val normalParamTypes = paramTypes.map { it ?: Any::class.java }.toTypedArray()
            ConstructorHookImpl(targetClass, *normalParamTypes)
                .apply {
                    onReplace {
                        val invArgs = arrayOf(this, *args)
                        value.invoke(this@HookEntity, *invArgs)
                        thisObject
                    }
                    if (value.getAnnotation(HookOnce::class.java) != null) {
                        onUnhook { unhook() }
                    }
                }.startHook()
        }
    }

    /**
     * 获取被指定注解标注的方法集合
     * @param a  a extends Annotation
     * @return Map
     */
    @Throws(IllegalArgumentException::class)
    private fun <A : Annotation> getAnnotationMethod(a: Class<A>): Map<IdentifyKey<A>, Method> {
        val map = mutableMapOf<IdentifyKey<A>, Method>()
        for (method in mineMethods) {
            val annotation = method.getAnnotation(a) ?: continue

            val mineParamsTypes = method.parameterTypes
            if (mineParamsTypes.isEmpty()) {
                throw IllegalArgumentException("parameterTypes empty.")
            }
            if (mineParamsTypes.first() != MethodParam::class.java) {
                throw IllegalArgumentException("parameterTypes[0] must be `MethodParam`.")
            }

            map[IdentifyKey("$method", annotation)] = method
        }
        return map
    }

    /**
     * 替换 [Param] 注解, 获取目标方法中的的真实参数列表
     * @param method 目标方法
     * @return Array
     */
    private fun getTargetMethodParamTypes(method: Method): Array<Class<*>?> {
        val parameterAnnotations = method.parameterAnnotations
        val parameterTypes = method.parameterTypes

        // 只含注解参数的情况下
        return if (parameterAnnotations.size < parameterTypes.size) {
            getTargetMethodParamTypesOnlyAnnotations(parameterAnnotations, parameterTypes)
        } else {
            getTargetMethodParamTypesNormal(parameterAnnotations, parameterTypes)
        }
    }

    /**
     * 部分情况下 [parameterAnnotations] 只会返回含有注解的参数
     * ```
     * val targetMethodParamTypes = method.parameterTypes
     * val parameterAnnotations = method.parameterAnnotations
     *
     * // 通常情况下 targetMethodParamTypes.size == parameterAnnotations.size 的结果应该是 `true`
     * // 而某些情况下 targetMethodParamTypes.size > parameterAnnotations.size 的结果才是 `true`
     * // 因为 `parameterAnnotations.size == 被注解标注了的参数的数量` 而那些未被注解的参数本来应该是空数组的，但是它却没了。
     * ```
     * @param parameterAnnotations 注解数组
     * @param parameterTypes 参数数组
     */
    private fun getTargetMethodParamTypesOnlyAnnotations(
        parameterAnnotations: Array<Array<Annotation>>,
        parameterTypes: Array<Class<*>>
    ): Array<Class<*>?> {
        // 整理参数, 将第一个参数`MethodParam`移除
        val paramTypes = parameterTypes.toMutableList().apply { removeAt(0) }

        var index = 0
        return paramTypes.map { clazz ->
            if (clazz == Any::class.java) {
                val param = parameterAnnotations[index++].filterIsInstance<Param>()
                    .ifEmpty { return@map clazz } // 如果某个参数没有@Param注解, 直接return
                findTargetParamClass(param[0].name)// 寻找注解类
            } else {
                clazz
            }
        }.toTypedArray()
    }

    /**
     * 正常情况下 [parameterAnnotations] 能获取到全部参数的注解, 未被注解标注的参数是一个空注解数组
     *
     * @param parameterAnnotations 注解数组
     * @param parameterTypes 参数数组
     */
    private fun getTargetMethodParamTypesNormal(
        parameterAnnotations: Array<Array<Annotation>>,
        parameterTypes: Array<Class<*>?>
    ): Array<Class<*>?> {
        // 整理参数、参数注解列表, 将第一个参数`MethodParam`移除
        val paramAnnotations = parameterAnnotations.toMutableList().apply { removeAt(0) }
        val paramTypes = parameterTypes.toMutableList().apply { removeAt(0) }

        // 替换 @Param 指定的参数类型
        val finalParamTypes = paramTypes.mapIndexed { index, clazz ->
            if (paramAnnotations[index].isEmpty()) return@mapIndexed clazz // 如果某个参数没有注解
            val param = paramAnnotations[index].filterIsInstance<Param>()
                .ifEmpty { return@mapIndexed clazz } // 如果某个参数有注解, 但没有@Param注解, 直接return
            findTargetParamClass(param[0].name) // 寻找注解类
        }.toTypedArray()

        return finalParamTypes
    }

    /**
     * 查找方法参数注解中的类，未找到则用 null 代替。
     */
    private fun findTargetParamClass(
        name: String,
        classLoader: ClassLoader? = null,
    ): Class<*>? {
        val optName = simpleName(name)
        if (optName.isEmpty() || optName.isBlank() || optName == "null") {
            return null
        }

        //
        return try {
            XposedHelpers.findClass(optName, classLoader ?: param.classLoader)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 勾住所有普通方法
     */
    private fun hookTargetAllMethod() {
        // 所有普通方法
        if (this@HookEntity is CallMethods) {
            helper?.methodAll {
                onBefore {
                    callOnBeforeMethods(this)
                }
                onAfter {
                    thisObject ?: return@onAfter
                    callOnAfterMethods(this)
                }
            }
        }
    }

    /**
     * 勾住所有构造方法
     */
    private fun hookTargetAllConstructor() {
        // 所有构造方法
        if (this@HookEntity is CallConstructors) {
            helper?.constructorAll {
                onBefore {
                    callOnBeforeConstructors(this)
                }
                onAfter {
                    thisObject ?: return@onAfter
                    callOnAfterConstructors(this)
                }
            }
        }
    }

    private data class IdentifyKey<T>(
        val identify: String,
        val key: T,
    )

    /**
     * 对于目标类某个普通方法的挂勾，等价于 [XC_MethodHook.beforeHookedMethod]。
     *
     * 被标注的方法第一个参数需要是 [MethodParam]，其后才是原方法参数列表；
     * 若某个方法中的参数类型无法直接被引用，可参考使用 [Param] 注解直接指定。
     *
     * @param name Hook目标方法名
     *
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class OnBefore(vararg val name: String)

    /**
     * 对于目标类某个普通方法的挂勾，等价于 [XC_MethodHook.afterHookedMethod]。
     *
     * 被标注的方法第一个参数需要是 [MethodParam]，其后才是原方法参数列表；
     * 若某个方法中的参数类型无法直接被引用，可参考使用 [Param] 注解直接指定。
     *
     * @param name Hook目标方法名
     *
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class OnAfter(vararg val name: String)

    /**
     * 对于目标类某个普通方法的挂勾，等价于 [XC_MethodReplacement.replaceHookedMethod]。
     *
     * 被标注的方法第一个参数需要是 [MethodParam]，其后才是原方法参数列表；
     * 若某个方法中的参数类型无法直接被引用，可参考使用 [Param] 注解直接指定。
     *
     * @param name Hook目标方法名
     *
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class OnReplace(vararg val name: String)

    /**
     * 对于目标类某个构造方法的挂勾，等价于 [XC_MethodHook.beforeHookedMethod]。
     *
     * 被标注的方法第一个参数需要是 [MethodParam]，其后才是原方法参数列表；
     * 若某个方法中的参数类型无法直接被引用，可参考使用 [Param] 注解直接指定。
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class OnConstructorBefore

    /**
     * 对于目标类某个构造方法的挂勾，等价于 [XC_MethodHook.afterHookedMethod]。
     *
     * 被标注的方法第一个参数需要是 [MethodParam]，其后才是原方法参数列表；
     * 若某个方法中的参数类型无法直接被引用，可参考使用 [Param] 注解直接指定。
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class OnConstructorAfter

    /**
     * 对于目标类某个构造方法的挂勾，等价于 [XC_MethodReplacement.replaceHookedMethod]。
     *
     * 被标注的方法第一个参数需要是 [MethodParam]，其后才是原方法参数列表；
     * 若某个方法中的参数类型无法直接被引用，可参考使用 [Param] 注解直接指定。
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class OnConstructorReplace

    /**
     * 对于目标方法中出现的不确定参数类型，默认模糊匹配。
     *
     * 若某个参数属于宿主如：`com.sample.User`，书写时无法直接通过`import`引入，可使用该注解手动指定。
     * 而该类型则需要使用[java.lang.Object]/[kotlin.Any]顶层类代替。
     *
     * ```
     * @OnBefore("exampleMethod")
     * fun exampleMethodBefore(
     *     params: MethodParam,
     *     @Param("com.sample.User") user:Any?  //user!!.javaClass == com.sample.User (Mandatory type)
     *     @Param arg1:Any?  //arg1!!.javaClass == Any (Any type)
     *     @Param("java.lang.Object") arg2:Any?  //arg2!!.javaClass == java.lang.Object/kotlin.Any (Mandatory type)
     * ){
     *     hookBlockRunning(params){
     *         //some logic..
     *     }.onFailure {
     *         XplerLog.e(it)
     *    }
     * }
     * ```
     *
     * @param name 应该是一个完整的类名, 如: com.sample.User；
     *             允许为 `"null"` 或 `""` 字符串，将模糊匹配任意类型。
     */
    @Target(AnnotationTarget.VALUE_PARAMETER)
    protected annotation class Param(val name: String = "null")

    /**
     * [Param]的衍生类，对于处理某些情况下原始类型本身是[java.lang.Object]/[kotlin.Any]的情况。
     *
     * [KeepParam]出现的原因见 [HookEntity.getTargetMethodParamTypesOnlyAnnotations] 的解释。
     *
     * 实际上它只是为了给注解二维数组占位，并未有任何实际意义。
     *
     * 或者只要你愿意，可以为每一个原始类型这样注解：
     * ```
     * @OnBefore
     * fun test(
     *      //@KeepParam any: Any?, //与下一行目的相同
     *      @Param("java.lang.Object") any: Any?,
     *      @Param("com.test.User") user: Any?,
     * ){
     *      //hook logic
     * }
     * ```
     */
    @Target(AnnotationTarget.VALUE_PARAMETER)
    protected annotation class KeepParam

    /**
     * 目标类的某个方法类型，精确匹配用得上。
     *
     * 数组类型见: [Class.getName]
     *
     * @param name 类型字符串，应该是一个全类名
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class ReturnType(val name: String = "")

    /**
     * 一次性的Hook注解。
     *
     * 需要搭配 [OnBefore]、[OnAfter]、[OnReplace]、[OnConstructorBefore]、[OnConstructorAfter]、[OnConstructorReplace] 使用。
     *
     * 被该标注的某个逻辑方法会在执行一次Hook后立即解开。
     */
    @Target(AnnotationTarget.FUNCTION)
    protected annotation class HookOnce
}