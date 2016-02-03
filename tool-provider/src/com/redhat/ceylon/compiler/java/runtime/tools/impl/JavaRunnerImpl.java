package com.redhat.ceylon.compiler.java.runtime.tools.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;

import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.cmr.ceylon.CeylonUtils;
import com.redhat.ceylon.common.JVMModuleUtil;
import com.redhat.ceylon.compiler.java.runtime.model.OverridesRuntimeResolver;
import com.redhat.ceylon.compiler.java.runtime.tools.JavaRunner;
import com.redhat.ceylon.compiler.java.runtime.tools.JavaRunnerOptions;
import com.redhat.ceylon.compiler.java.runtime.tools.RunnerOptions;

public class JavaRunnerImpl implements JavaRunner {
    private String module;
    
    private BaseModuleLoaderImpl moduleLoader;
    private ClassLoader moduleClassLoader;
    private String run;
    
    public JavaRunnerImpl(RunnerOptions options, String module, String version){
        this.module = module;
        
        RepositoryManager repositoryManager = CeylonUtils.repoManager()
                .userRepos(options.getUserRepositories())
                .systemRepo(options.getSystemRepository())
                .offline(options.isOffline())
                .noDefaultRepos(options.isNoDefaultRepositories())
                .overrides(options.getOverrides())
                .upgradeDist(!options.isDowngradeDist())
                .logger(new CmrLogger(options.isVerbose("cmr")))
                .buildManager();
        new OverridesRuntimeResolver(repositoryManager.getOverrides()).installInThreadLocal();
        ClassLoader delegateClassLoader = null;
        if(options instanceof JavaRunnerOptions){
            delegateClassLoader = ((JavaRunnerOptions) options).getDelegateClassLoader();
        }
        
        moduleLoader = new FlatpathModuleLoader(repositoryManager, delegateClassLoader, options.getExtraModules(), options.isVerbose("cmr"));
        moduleClassLoader = moduleLoader.loadModule(module, version);
        run = options.getRun();
    }

    @Override
    public void run(String... arguments){
        if(moduleClassLoader == null)
            throw new ceylon.language.AssertionError("Cannot call run method after cleanup is called");
        // now load and invoke the main class
        invokeMain(module, arguments);
    }
    
    @Override
    public ClassLoader getModuleClassLoader() {
        if(moduleClassLoader == null)
            throw new ceylon.language.AssertionError("Cannot get class loader after cleanup is called");
        return moduleClassLoader;
    }

    @Override
    public void cleanup() {
        moduleLoader.cleanup();
        moduleLoader = null;
        moduleClassLoader = null;
    }
    
    // for tests
    public URL[] getClassLoaderURLs() {
        return moduleLoader.getClassLoaderURLs(module);
    }
    
    private void invokeMain(String module, String[] arguments) {
        String className = JVMModuleUtil.javaClassNameFromCeylon(module, run);
        
        try {
            Class<?> runClass = moduleClassLoader.loadClass(className);
            Method main = runClass.getMethod("main", String[].class);
            Thread currentThread = Thread.currentThread();
            ClassLoader oldCcl = currentThread.getContextClassLoader();
            try{
                currentThread.setContextClassLoader(moduleClassLoader);
                main.invoke(null, (Object)arguments);
            }finally{
                currentThread.setContextClassLoader(oldCcl);
            }

        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot find main class for module "+module+": "+className, e);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException 
                | NoSuchMethodException | SecurityException e) {
            throw new RuntimeException("Failed to invoke main method for module "+module+": "+className, e);
        }
    }

}
