/*
 * Copyright 2012-2013 eBay Software Foundation and ios-driver committers
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.uiautomation.ios;

import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.Response;
import org.uiautomation.ios.UIAModels.Session;
import org.uiautomation.ios.UIAModels.configuration.CommandConfiguration;
import org.uiautomation.ios.UIAModels.configuration.DriverConfiguration;
import org.uiautomation.ios.application.APPIOSApplication;
import org.uiautomation.ios.application.AppleLanguage;
import org.uiautomation.ios.application.IOSRunningApplication;
import org.uiautomation.ios.application.NoSuchLanguageCodeException;
import org.uiautomation.ios.application.NoSuchLocaleException;
import org.uiautomation.ios.command.configuration.Configuration;
import org.uiautomation.ios.command.configuration.DriverConfigurationStore;
import org.uiautomation.ios.communication.WebDriverLikeCommand;
import org.uiautomation.ios.communication.device.DeviceVariation;
import org.uiautomation.ios.drivers.IOSDualDriver;
import org.uiautomation.ios.instruments.InstrumentsFailedToStartException;
import org.uiautomation.ios.logging.IOSLogManager;
import org.uiautomation.ios.utils.ApplicationCrashDetails;
import org.uiautomation.ios.utils.ClassicCommands;
import org.uiautomation.ios.utils.IOSVersion;
import org.uiautomation.ios.utils.ZipUtils;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.UUID;
import java.util.logging.Logger;

public class ServerSideSession extends Session {

  private static final String DEFAULT_LANGUAGE_CODE = "en";
  private static final String DEFAULT_LOCALE = "en_GB";

  private static final Logger log = Logger.getLogger(ServerSideSession.class.getName());
  public final IOSServerManager server;
  private final IOSCapabilities capabilities;
  private final IOSDualDriver driver;
  private final IOSServerConfiguration options;
  private final DriverConfiguration configuration;
  private IOSRunningApplication application;
  private Device device;
  private boolean sessionCrashed;
  private ApplicationCrashDetails applicationCrashDetails;
  private final IOSLogManager logManager;
  private Response capabilityCachedResponse;
  private boolean decorated = false;

  ServerSideSession(IOSServerManager server, IOSCapabilities desiredCapabilities,
                    IOSServerConfiguration options) throws SessionNotInitializedException {
    super(UUID.randomUUID().toString());
    this.server = server;
    this.capabilities = desiredCapabilities;
    this.options = options;

    try {
      logManager = new IOSLogManager(capabilities.getLoggingPreferences());
    } catch (Exception ex) {
      ex.printStackTrace();
      throw new SessionNotCreatedException("Cannot create logManager", ex);
    }

    this.sessionCrashed = false;
    this.applicationCrashDetails = null;

    ensureLanguage();
    ensureLocale();

    try {
      device = server.findAndReserveMatchingDevice(desiredCapabilities);

      // extract application from capabilities if necessary
      URL url = desiredCapabilities.getAppURL();
      if (url != null) {
        application = extractFromCapabilities();
      } else {
        application = server.findAndCreateInstanceMatchingApplication(desiredCapabilities);
      }

      updateCapabilitiesWithSensibleDefaults();

      driver = new IOSDualDriver(this);
      configuration = new DriverConfigurationStore();


    } catch (Exception e) {
      if (device != null) {
        device.release();
      }
      throw new SessionNotInitializedException(e.getMessage(), e);
    }
  }

  private void ensureLanguage() throws NoSuchLanguageCodeException {
    String languageCode = capabilities.getLanguage();

    // if language code is not specified in the capabilities
    if (languageCode == null || languageCode.trim().length() == 0) {
      // then use the default language code "en"
      capabilities.setLanguage(DEFAULT_LANGUAGE_CODE);
    } else {

      AppleLanguage[] values = AppleLanguage.values();
      boolean languageCodeFound = false;
      for (AppleLanguage value : values) {
        if (value.getIsoCode().equals(languageCode)) {
          languageCodeFound = true;
        }
      }
      if (!languageCodeFound) {
        throw new NoSuchLanguageCodeException(languageCode);
      }
    }
  }

  private void ensureLocale() throws NoSuchLocaleException {
    String locale = capabilities.getLocale();

    // if locale is not specified in the capabilities
    if (locale == null || locale.trim().length() == 0) {
      // then use the default locale "en_GB"
      capabilities.setLocale(DEFAULT_LOCALE);
    }
  }

  private void updateCapabilitiesWithSensibleDefaults() {
    // update capabilities and put default values in the missing fields.
    capabilities.setBundleId(application.getBundleId());
    // TODO device.getSDK()
    if (capabilities.getSDKVersion() == null) {
      capabilities.setSDKVersion(ClassicCommands.getDefaultSDK());
    } else {
      String version = capabilities.getSDKVersion();

      if (!new IOSVersion(version).isGreaterOrEqualTo("5.0")) {
        throw new SessionNotCreatedException(
            version + " is too old. Only support SDK 5.0 and above.");
      }
      if (!server.getHostInfo().getInstalledSDKs().contains(version)) {
        throw new SessionNotCreatedException(
            "Cannot start on version " + version + ".Installed: "
            + server.getHostInfo().getInstalledSDKs());
      }
    }
    if (capabilities.getDeviceVariation() == null) {
      // use a variation that matches the SDK, Regular wouldn't work for iOS 7
      String sdkVersion = capabilities.getSDKVersion();
      capabilities.setDeviceVariation(DeviceVariation.getCompatibleVersion(
          capabilities.getDevice(), sdkVersion));
    }
  }

  private IOSRunningApplication extractFromCapabilities() {
    URL appURL = capabilities.getAppURL();
    if (!Configuration.BETA_FEATURE) {
      Configuration.off();
    }

    try {
      File appFile = ZipUtils.extractAppFromURL(appURL);
      if (appFile.getName().endsWith(".app")) {
        capabilities.setCapability(IOSCapabilities.SIMULATOR, true);
      }
      APPIOSApplication app = APPIOSApplication.createFrom(appFile);
      AppleLanguage lang = AppleLanguage.valueOf(capabilities.getLanguage());
      return app.createInstance(lang);
    } catch (Exception ex) {
      throw new SessionNotCreatedException("cannot create app from " + appURL + ": " + ex);
    }

  }

  public boolean isSafariRealDevice() {
    Device device = getDevice();
    if (device instanceof RealDevice) {
      if ("com.apple.mobilesafari".equals(getCapabilities().getBundleId())) {
        return true;
      }
    }
    return false;
  }

  public IOSCapabilities getCapabilities() {
    return capabilities;
  }

  public Device getDevice() {
    return device;
  }

  public CommandConfiguration configure(WebDriverLikeCommand command) {
    return configuration.configure(command);
  }

  public IOSRunningApplication getApplication() {
    return application;
  }

  public boolean hasCrashed() {
    return sessionCrashed;
  }

  public void sessionHasCrashed(String log) {
    sessionCrashed = true;
    applicationCrashDetails = new ApplicationCrashDetails(log);
    stop();
  }

  public ApplicationCrashDetails getCrashDetails() {
    return applicationCrashDetails;
  }

  public IOSServerManager getIOSServerManager() {
    return server;
  }

  public IOSServerConfiguration getOptions() {
    return options;
  }

  public IOSDualDriver getDualDriver() {
    return driver;
  }

  public void start(long timeOut) throws InstrumentsFailedToStartException {
    driver.start(timeOut);
  }

  public void stop() {
    if (device != null) {
      device.release();
    }

    if (driver != null) {
      driver.stop();
    }
  }

  public URL getURL() {
    URL url;
    try {
      url = new URL("http://localhost:" + server.getHostInfo().getPort() + "/wd/hub");
    } catch (MalformedURLException e) {
      throw new WebDriverException(e.getMessage(), e);
    }
    return url;
  }

  public IOSLogManager getLogManager() {
    return logManager;
  }

  public void setCapabilityCachedResponse(Response capabilityCachedResponse) {
    this.capabilityCachedResponse = capabilityCachedResponse;
  }


  public Response getCachedCapabilityResponse() {
    return capabilityCachedResponse;
  }

  public boolean hasBeenDecorated() {
    return decorated;
  }

  public void setDecorated(boolean dec) {
    decorated = dec;
  }
}
