package org.thoughtcrime.securesms.dependencies;

import android.app.Application;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.KbsEnclave;
import org.thoughtcrime.securesms.components.TypingStatusRepository;
import org.thoughtcrime.securesms.components.TypingStatusSender;
import org.thoughtcrime.securesms.database.DatabaseObserver;
import org.thoughtcrime.securesms.groups.GroupsV2Authorization;
import org.thoughtcrime.securesms.groups.GroupsV2AuthorizationMemoryValueCache;
import org.thoughtcrime.securesms.groups.v2.processing.GroupsV2StateProcessor;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository;
import org.thoughtcrime.securesms.messages.BackgroundMessageRetriever;
import org.thoughtcrime.securesms.messages.IncomingMessageObserver;
import org.thoughtcrime.securesms.messages.IncomingMessageProcessor;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;
import org.thoughtcrime.securesms.recipients.LiveRecipientCache;
import org.thoughtcrime.securesms.service.TrimThreadsByDateManager;
import org.thoughtcrime.securesms.util.EarlyMessageCache;
import org.thoughtcrime.securesms.util.FrameRateTracker;
import org.thoughtcrime.securesms.util.Hex;
import org.thoughtcrime.securesms.util.IasKeyStore;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.KeyBackupService;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;

/**
 * Location for storing and retrieving application-scoped singletons. Users must call
 * {@link #init(Provider)} before using any of the methods, preferably early on in
 * {@link Application#onCreate()}.
 *
 * All future application-scoped singletons should be written as normal objects, then placed here
 * to manage their singleton-ness.
 */
public class ApplicationDependencies {

  private static final Object LOCK                    = new Object();
  private static final Object FRAME_RATE_TRACKER_LOCK = new Object();

  private static Provider                 dependencyProvider;
  private static MessageNotifier          messageNotifier;
  private static TrimThreadsByDateManager trimThreadsByDateManager;

  private static volatile SignalServiceAccountManager  accountManager;
  private static volatile SignalServiceMessageSender   messageSender;
  private static volatile SignalServiceMessageReceiver messageReceiver;
  private static volatile IncomingMessageObserver      incomingMessageObserver;
  private static volatile IncomingMessageProcessor     incomingMessageProcessor;
  private static volatile BackgroundMessageRetriever   backgroundMessageRetriever;
  private static volatile LiveRecipientCache           recipientCache;
  private static volatile JobManager                   jobManager;
  private static volatile FrameRateTracker             frameRateTracker;
  private static volatile MegaphoneRepository          megaphoneRepository;
  private static volatile GroupsV2Authorization        groupsV2Authorization;
  private static volatile GroupsV2StateProcessor       groupsV2StateProcessor;
  private static volatile GroupsV2Operations           groupsV2Operations;
  private static volatile EarlyMessageCache            earlyMessageCache;
  private static volatile TypingStatusRepository       typingStatusRepository;
  private static volatile TypingStatusSender           typingStatusSender;
  private static volatile DatabaseObserver             databaseObserver;

  @MainThread
  public static void init(@NonNull Provider provider) {
    synchronized (LOCK) {
      if (ApplicationDependencies.dependencyProvider != null) {
        throw new IllegalStateException("Already initialized!");
      }

      ApplicationDependencies.dependencyProvider       = provider;
      ApplicationDependencies.messageNotifier          = provider.provideMessageNotifier();
      ApplicationDependencies.trimThreadsByDateManager = provider.provideTrimThreadsByDateManager();
    }
  }

  public static @NonNull Provider getProvider() {
    if (dependencyProvider == null) {
      throw new IllegalStateException("ApplicationDependencies not initialized yet");
    }
    return dependencyProvider;
  }

  public static @NonNull Application getApplication() {
    return ApplicationContext.getInstance();
  }

  public static @NonNull SignalServiceAccountManager getSignalServiceAccountManager() {
    if (accountManager == null) {
      synchronized (LOCK) {
        if (accountManager == null) {
          accountManager = getProvider().provideSignalServiceAccountManager();
        }
      }
    }

    return accountManager;
  }

  public static @NonNull GroupsV2Authorization getGroupsV2Authorization() {
    if (groupsV2Authorization == null) {
      synchronized (LOCK) {
        if (groupsV2Authorization == null) {
          GroupsV2Authorization.ValueCache authCache = new GroupsV2AuthorizationMemoryValueCache(SignalStore.groupsV2AuthorizationCache());
          groupsV2Authorization = new GroupsV2Authorization(getSignalServiceAccountManager().getGroupsV2Api(), authCache);
        }
      }
    }

    return groupsV2Authorization;
  }

  public static @NonNull GroupsV2Operations getGroupsV2Operations() {
    if (groupsV2Operations == null) {
      synchronized (LOCK) {
        if (groupsV2Operations == null) {
          groupsV2Operations = getProvider().provideGroupsV2Operations();
        }
      }
    }

    return groupsV2Operations;
  }

  public static @NonNull KeyBackupService getKeyBackupService(@NonNull KbsEnclave enclave) {
    return getSignalServiceAccountManager().getKeyBackupService(IasKeyStore.getIasKeyStore(getApplication()),
                                                                enclave.getEnclaveName(),
                                                                Hex.fromStringOrThrow(enclave.getServiceId()),
                                                                enclave.getMrEnclave(),
                                                                10);
  }

  public static @NonNull GroupsV2StateProcessor getGroupsV2StateProcessor() {
    if (groupsV2StateProcessor == null) {
      synchronized (LOCK) {
        if (groupsV2StateProcessor == null) {
          groupsV2StateProcessor = new GroupsV2StateProcessor(getApplication());
        }
      }
    }

    return groupsV2StateProcessor;
  }

  public static @NonNull SignalServiceMessageSender getSignalServiceMessageSender() {
    synchronized (LOCK) {
      if (messageSender == null) {
        messageSender = getProvider().provideSignalServiceMessageSender();
      } else {
        messageSender.update(
            IncomingMessageObserver.getPipe(),
            IncomingMessageObserver.getUnidentifiedPipe(),
            TextSecurePreferences.isMultiDevice(getApplication()));
      }
    }

    return messageSender;
  }

  public static @NonNull SignalServiceMessageReceiver getSignalServiceMessageReceiver() {
    if (messageReceiver == null) {
      synchronized (LOCK) {
        if (messageReceiver == null) {
          messageReceiver = getProvider().provideSignalServiceMessageReceiver();
        }
      }
    }

    return messageReceiver;
  }

  public static void resetSignalServiceMessageReceiver() {
    synchronized (LOCK) {
      messageReceiver = null;
    }
  }

  public static @NonNull SignalServiceNetworkAccess getSignalServiceNetworkAccess() {
    return getProvider().provideSignalServiceNetworkAccess();
  }

  public static @NonNull IncomingMessageProcessor getIncomingMessageProcessor() {
    if (incomingMessageProcessor == null) {
      synchronized (LOCK) {
        if (incomingMessageProcessor == null) {
          incomingMessageProcessor = getProvider().provideIncomingMessageProcessor();
        }
      }
    }

    return incomingMessageProcessor;
  }

  public static @NonNull BackgroundMessageRetriever getBackgroundMessageRetriever() {
    if (backgroundMessageRetriever == null) {
      synchronized (LOCK) {
        if (backgroundMessageRetriever == null) {
          backgroundMessageRetriever = getProvider().provideBackgroundMessageRetriever();
        }
      }
    }

    return backgroundMessageRetriever;
  }

  public static @NonNull LiveRecipientCache getRecipientCache() {
    if (recipientCache == null) {
      synchronized (LOCK) {
        if (recipientCache == null) {
          recipientCache = getProvider().provideRecipientCache();
        }
      }
    }

    return recipientCache;
  }

  public static @NonNull JobManager getJobManager() {
    if (jobManager == null) {
      synchronized (LOCK) {
        if (jobManager == null) {
          jobManager = getProvider().provideJobManager();
        }
      }
    }

    return jobManager;
  }

  public static @NonNull FrameRateTracker getFrameRateTracker() {
    if (frameRateTracker == null) {
      synchronized (FRAME_RATE_TRACKER_LOCK) {
        if (frameRateTracker == null) {
          frameRateTracker = getProvider().provideFrameRateTracker();
        }
      }
    }

    return frameRateTracker;
  }

  public static @NonNull MegaphoneRepository getMegaphoneRepository() {
    if (megaphoneRepository == null) {
      synchronized (LOCK) {
        if (megaphoneRepository == null) {
          megaphoneRepository = getProvider().provideMegaphoneRepository();
        }
      }
    }

    return megaphoneRepository;
  }

  public static @NonNull EarlyMessageCache getEarlyMessageCache() {
    if (earlyMessageCache == null) {
      synchronized (LOCK) {
        if (earlyMessageCache == null) {
          earlyMessageCache = getProvider().provideEarlyMessageCache();
        }
      }
    }

    return earlyMessageCache;
  }

  public static @NonNull MessageNotifier getMessageNotifier() {
    return messageNotifier;
  }

  public static @NonNull IncomingMessageObserver getIncomingMessageObserver() {
    if (incomingMessageObserver == null) {
      synchronized (LOCK) {
        if (incomingMessageObserver == null) {
          incomingMessageObserver = getProvider().provideIncomingMessageObserver();
        }
      }
    }

    return incomingMessageObserver;
  }

  public static @NonNull TrimThreadsByDateManager getTrimThreadsByDateManager() {
      return trimThreadsByDateManager;
  }

  public static TypingStatusRepository getTypingStatusRepository() {
    if (typingStatusRepository == null) {
      typingStatusRepository = getProvider().provideTypingStatusRepository();
    }

    return typingStatusRepository;
  }

  public static TypingStatusSender getTypingStatusSender() {
    if (typingStatusSender == null) {
      typingStatusSender = getProvider().provideTypingStatusSender();
    }

    return typingStatusSender;
  }

  public static @NonNull DatabaseObserver getDatabaseObserver() {
    if (databaseObserver == null) {
      synchronized (LOCK) {
        if (databaseObserver == null) {
          databaseObserver = getProvider().provideDatabaseObserver();
        }
      }
    }

    return databaseObserver;
  }

  public interface Provider {
    @NonNull GroupsV2Operations provideGroupsV2Operations();
    @NonNull SignalServiceAccountManager provideSignalServiceAccountManager();
    @NonNull SignalServiceMessageSender provideSignalServiceMessageSender();
    @NonNull SignalServiceMessageReceiver provideSignalServiceMessageReceiver();
    @NonNull SignalServiceNetworkAccess provideSignalServiceNetworkAccess();
    @NonNull IncomingMessageProcessor provideIncomingMessageProcessor();
    @NonNull BackgroundMessageRetriever provideBackgroundMessageRetriever();
    @NonNull LiveRecipientCache provideRecipientCache();
    @NonNull JobManager provideJobManager();
    @NonNull FrameRateTracker provideFrameRateTracker();
    @NonNull MegaphoneRepository provideMegaphoneRepository();
    @NonNull EarlyMessageCache provideEarlyMessageCache();
    @NonNull MessageNotifier provideMessageNotifier();
    @NonNull IncomingMessageObserver provideIncomingMessageObserver();
    @NonNull TrimThreadsByDateManager provideTrimThreadsByDateManager();
    @NonNull TypingStatusRepository provideTypingStatusRepository();
    @NonNull TypingStatusSender provideTypingStatusSender();
    @NonNull DatabaseObserver provideDatabaseObserver();
  }
}
