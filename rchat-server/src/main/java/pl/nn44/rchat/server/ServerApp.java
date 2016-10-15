package pl.nn44.rchat.server;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.metadata.XmlRpcSystemImpl;
import org.apache.xmlrpc.server.PropertyHandlerMapping;
import org.apache.xmlrpc.server.XmlRpcErrorLogger;
import org.apache.xmlrpc.server.XmlRpcServerConfigImpl;
import org.apache.xmlrpc.webserver.XmlRpcServletServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.remoting.caucho.BurlapServiceExporter;
import org.springframework.remoting.caucho.HessianServiceExporter;
import org.springframework.web.HttpRequestHandler;
import pl.nn44.rchat.protocol.ChatService;
import pl.nn44.rchat.server.impl.BestChatService;
import pl.nn44.rchat.server.page.MainPageController;
import pl.nn44.rchat.server.page.PlainErrorController;

@Configuration
@EnableAutoConfiguration
public class ServerApp {

    public static void main(String[] args) {
        SpringApplication.run(ServerApp.class, args);
    }

    @Bean
    public ChatService chatService() {
        return new BestChatService();
    }

    @Bean(name = "/hessian")
    public HttpRequestHandler hessianChatController() {
        HessianServiceExporter exporter = new HessianServiceExporter();
        exporter.setService(chatService());
        exporter.setServiceInterface(ChatService.class);
        return exporter;
    }

    @Bean(name = "/burlap")
    public HttpRequestHandler burlapChatController() {
        BurlapServiceExporter exporter = new BurlapServiceExporter();
        exporter.setService(chatService());
        exporter.setServiceInterface(ChatService.class);
        return exporter;
    }

    @Bean(name = "/xml-rpc")
    public HttpRequestHandler xmlRpcChatController() throws XmlRpcException {
        int cores = Runtime.getRuntime().availableProcessors();

        XmlRpcServerConfigImpl config = new XmlRpcServerConfigImpl();
        config.setEncoding(XmlRpcServerConfigImpl.UTF8_ENCODING);
        config.setEnabledForExceptions(true);
        config.setEnabledForExtensions(true);
        config.setKeepAliveEnabled(true);

        PropertyHandlerMapping handlerMapping = new PropertyHandlerMapping();
        handlerMapping.setRequestProcessorFactoryFactory(pClass -> pRequest -> chatService());
        handlerMapping.addHandler("ChatService", ChatService.class);
        XmlRpcSystemImpl.addSystemHandler(handlerMapping);

        XmlRpcServletServer server = new XmlRpcServletServer();
        server.setConfig(config);
        server.setErrorLogger(new XmlRpcErrorLogger());
        server.setMaxThreads(cores * 5);
        server.setHandlerMapping(handlerMapping);

        return server::execute;
    }

    @Bean
    public MainPageController mainPageController() {
        return new MainPageController();
    }

    @Bean
    public ErrorController errorPageController() {
        return new PlainErrorController();
    }
}
