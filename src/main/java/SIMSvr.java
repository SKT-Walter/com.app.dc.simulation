import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.PropertyConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.gw.common.utils.Consts;
import com.gw.common.utils.ServerGwWrapper;

@SpringBootApplication
@ComponentScan({ "com.gw.common.utils", "com.app.dc", "com.app.common.db" })
@EnableAutoConfiguration
@EnableScheduling
public class SIMSvr {
    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();
        PropertyConfigurator.configureAndWatch("./config/log4j.ini", 1000);
        Logger logger = LoggerFactory.getLogger(SIMSvr.class);
        try {
            ServerGwWrapper.Start(SIMSvr.class, "./config/application.properties", args);
        } catch (Exception e) {
            logger.error(Consts.ServerName + " start error.", e);
        }
    }
}
