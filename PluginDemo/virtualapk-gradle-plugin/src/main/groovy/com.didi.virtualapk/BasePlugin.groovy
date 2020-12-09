package com.didi.virtualapk

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.internal.TaskFactory
import com.android.build.gradle.internal.TaskManager
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.variant.VariantFactory
import com.android.builder.core.VariantType
import com.didi.virtualapk.tasks.AssemblePlugin
import com.didi.virtualapk.utils.Log
import com.didi.virtualapk.utils.Reflect
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.internal.reflect.Instantiator
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.NameMatcher

import javax.inject.Inject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Base class of VirtualApk plugin, we create assemblePlugin task here
 * @author zhengtao
 */
public abstract class BasePlugin implements Plugin<Project> {

    protected Project project
    protected Instantiator instantiator
    protected TaskFactory taskFactory

    boolean checkVariantFactoryInvoked

    @Inject
    public BasePlugin(Instantiator instantiator, ToolingModelBuilderRegistry registry) {
        this.instantiator = instantiator
    }

    @Override
    public void apply(Project project) {
        this.project = project
        project.ext.set(Constants.GRADLE_3_1_0, false)

        try {
            Class.forName('com.android.builder.core.VariantConfiguration')
        } catch (Throwable e) {
            // com.android.tools.build:gradle:3.1.0
            project.ext.set(Constants.GRADLE_3_1_0, true)
        }

        AppPlugin appPlugin = project.plugins.findPlugin(AppPlugin)

        Reflect reflect = Reflect.on(appPlugin.variantManager)
        /**
         * Sy007注:
         * 代理VariantFactory的原因是为了监听preVariantWork的调用，preVariantWork 在创建Android Tasks之前
         * 具体在VariantManager#createAndroidTasks()中会调用preVariantWork
         * 为什么要监听preVariantWork的调用？
         * 这是因为监听Terminal 输入 gradlew 命令？
         */
        VariantFactory variantFactory = Proxy.newProxyInstance(this.class.classLoader, [VariantFactory.class] as Class[],
                new InvocationHandler() {
                    Object delegate = reflect.get('variantFactory')

                    @Override
                    Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ('preVariantWork' == method.name) {
                            checkVariantFactoryInvoked = true
                            Log.i 'VAPlugin', "Evaluating VirtualApk's configurations..."
                            //评估是否在terminal输入了assembleXxxPlugin 命令，true表示是，false则表示不是
                            boolean isBuildingPlugin = evaluateBuildingPlugin(appPlugin, project)
                            beforeCreateAndroidTasks(isBuildingPlugin)
                        }

                        return method.invoke(delegate, args)
                    }
                })
        reflect.set('variantFactory', variantFactory)

        project.extensions.create('virtualApk', VAExtention)

        if (project.extensions.extraProperties.get(Constants.GRADLE_3_1_0)) {
            TaskManager taskManager = Reflect.on(appPlugin).field('taskManager').get()
            taskFactory = taskManager.getTaskFactory()
        } else {
            taskFactory = Reflect.on('com.android.build.gradle.internal.TaskContainerAdaptor')
                    .create(project.tasks)
                    .get()
        }
        project.afterEvaluate {

            if (!checkVariantFactoryInvoked) {
                throw new RuntimeException('Evaluating VirtualApk\'s configurations has failed!')
            }

            android.applicationVariants.each { ApplicationVariantImpl variant ->
                if ('release' == variant.buildType.name) {
                    //assemble${productFlavors}${buildType}Plugin
                    String variantAssembleTaskName = variant.variantData.scope.getTaskName('assemble', 'Plugin')
                    //assemble${productFlavors}Plugin
                    def final variantPluginTaskName = createPluginTaskName(variantAssembleTaskName)
                    final def configAction = new AssemblePlugin.ConfigAction(project, variant)
                    //创建assemble${productFlavors}Plugin Task
                    taskFactory.create(variantPluginTaskName, AssemblePlugin, configAction)

                    Action action = new Action<Task>() {
                        @Override
                        void execute(Task task) {
                            //assemblePlugin 依赖 assemble${productFlavors}Plugin
                            //也就是说执行assemblePlugin时会执行所有的assemble${productFlavors}Plugin
                            //assemblePlugin只是一个快捷入口
                            task.dependsOn(variantPluginTaskName)
                        }
                    }

                    if (project.extensions.extraProperties.get(Constants.GRADLE_3_1_0)) {
                        //Task配置阶段执行action
                        taskFactory.configure("assemblePlugin", action)
                    } else {
                        taskFactory.named("assemblePlugin", action)
                    }
                }
            }
        }

        project.task('assemblePlugin', dependsOn: "assembleRelease", group: 'build', description: 'Build plugin apk')
    }

    String createPluginTaskName(String name) {
        if (name == 'assembleReleasePlugin') {
            return '_assemblePlugin'
        }
        return name.replace('Release', '')
    }

    /**
     * 该方法的作用判断是否在控制台执行gradlew assemble${productFlavors}Plugin 任务
     * @param appPlugin
     * @param project
     * @return
     */
    private boolean evaluateBuildingPlugin(AppPlugin appPlugin, Project project) {
        def startParameter = project.gradle.startParameter
        def targetTasks = startParameter.taskNames

        def pluginTasks = ['assemblePlugin'] as List<String>

        appPlugin.variantManager.buildTypes.each {
            def buildType = it.value.buildType
            if ('release' != buildType.name) {
                return
            }
            if (appPlugin.variantManager.productFlavors.isEmpty()) {
                return
            }

            appPlugin.variantManager.productFlavors.each {
                String variantName
                if (project.extensions.extraProperties.get(Constants.GRADLE_3_1_0)) {
                    variantName = Reflect.on('com.android.build.gradle.internal.core.VariantConfiguration')
                            .call('computeFullName', it.key, buildType, VariantType.DEFAULT, null)
                            .get()
                } else {
                    variantName = Reflect.on('com.android.builder.core.VariantConfiguration')
                            .call('computeFullName', it.key, buildType, VariantType.DEFAULT, null)
                            .get()
                }
                def variantPluginTaskName = createPluginTaskName("assemble${variantName.capitalize()}Plugin".toString())
                pluginTasks.add(variantPluginTaskName)
            }
        }

//        pluginTasks.each {
//            Log.i 'VAPlugin', "pluginTask: ${it}"
//        }
        //Sy007注:这里判断是否在terminal 输入 assemble${productFlavor}${buildType}Plugin
        boolean isBuildingPlugin = false
        NameMatcher nameMatcher = new NameMatcher()
        targetTasks.every {
            int index = it.lastIndexOf(":");
            String task = index >= 0 ? it.substring(index + 1) : it
            String taskName = nameMatcher.find(task, pluginTasks)
            if (taskName != null) {
//                Log.i 'VAPlugin', "Found task name '${taskName}' by given name '${it}'"
                isBuildingPlugin = true
                return false
            }
            return true
        }

        return isBuildingPlugin
    }

    protected abstract void beforeCreateAndroidTasks(boolean isBuildingPlugin)

    protected final VAExtention getVirtualApk() {
        return this.project.virtualApk
    }

    protected final AppExtension getAndroid() {
        return this.project.android
    }
}
