package cn.wubo.spring.ai.loom.agent;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 在 application.yml 加载之后、bean 创建之前运行，确保默认策略优先级低于用户配置。
 */
public class FileViewDefaultsBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    private final ConfigurableEnvironment environment;

    public FileViewDefaultsBeanFactoryPostProcessor(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 如果用户已在 application.yml 中配置 strategies，则跳过默认值
        if (environment.containsProperty("file.view.strategies")) {
            return;
        }

        Map<String, Object> defaults = new HashMap<>();
        // 各属性独立判断，用户只覆盖自己配置过的，未配置的仍使用默认值
        if (!environment.containsProperty("file.view.enabledListView")) {
            defaults.put("file.view.enabledListView", false);
        }
        if (!environment.containsProperty("file.view.api.enabledList")) {
            defaults.put("file.view.api.enabledList", false);
        }
        if (!environment.containsProperty("file.view.api.enabledUpload")) {
            defaults.put("file.view.api.enabledUpload", false);
        }
        if (!environment.containsProperty("file.view.api.enabledDelete")) {
            defaults.put("file.view.api.enabledDelete", false);
        }

        if (!environment.containsProperty("file.view.strategies")) {
            String[][] strategyDefs = {
                    {"glob:*.bpmn", "bpmn"},
                    {"glob:*.dmn", "dmn"},
                    {"glob:*.cmmn", "cmmn"},
                    {"glob:*.{c,cpp,cs,css,diff,go,graphql,ini,java,js,json,kt,less,lua,mk,m,pl,php,phtml,txt,py,pyrepl,r,rb,rs,scss,sh,sql,swift,ts,vb,wasm,xml,yaml,yml}", "code"},
                    {"glob:*.epub", "epub"},
                    {"glob:*.{jpg,png,bmp,gif,webp,svg,raw,heic,cr2,nef,orf,sr2}", "image"},
                    {"glob:*.md", "markdown"},
                    {"glob:*.pdf", "pdf"},
                    {"glob:*.xmind", "xmind"},
                    {"glob:*.docx", "docx"},
                    {"glob:*.csv", "csv"},
                    {"glob:*.xlsx", "excel"},
                    {"glob:*.pptx", "pptx"},
                    {"glob:*.{3dm,3ds,3mf,amf,bim,brep,dae,fbx,fcstd,gltf,ifc,iges,step,stl,obj,off,ply,wrl}", "o3d"},
                    {"glob:*.zip", "zip"},
                    {"glob:*.{dwg,dxf}", "cad"},
                    {"glob:*.{tif,tiff}", "tiff"},
                    {"glob:*.ofd", "ofd"},
            };
            for (int i = 0; i < strategyDefs.length; i++) {
                defaults.put("file.view.strategies[" + i + "].syntaxAndPattern", strategyDefs[i][0]);
                defaults.put("file.view.strategies[" + i + "].serviceName", strategyDefs[i][1]);
            }
        }

        PropertySource<?> defaultPropertySource = new MapPropertySource("file-view-defaults", defaults);
        environment.getPropertySources().addLast(defaultPropertySource);
    }
}
