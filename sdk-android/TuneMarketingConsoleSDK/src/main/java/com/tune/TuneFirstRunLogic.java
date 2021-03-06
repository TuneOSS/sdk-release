package com.tune;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;

/**
 * First Run Logic
 * During the First Run of a Tune Instance, there are a series of gates that need to be passed before
 * Tune starts to measure data.  Specifically, we would like for the <i>Install Referrer</i> and the
 * <i>Advertiser Id</i> to be present.
 * <ul>
 * <li>
 *     <b>Note</b> that there are numerous ways, and different timing when each of these values might become available.
 *     In order to accomplish this goal, this class brings all of these together into a single logical object.
 * </li>
 * <li>
 *     <b>Note</b> that beginning with v4.15.0, an additional check using the Google ReferrerDetails service will be used.
 * </li>
 * </ul>
 */
class TuneFirstRunLogic {
    static final int InstallReferrerResponse_GeneralException = -100;
    private static final int InstallReferrerClientConnectionTimeout = TuneConstants.FIRST_RUN_LOGIC_WAIT_TIME - 100;// we want to timeout before the thread resumes.

    private boolean gotAdvertisingId;
    private boolean gotGoogleReferrer;

    // Wait for the BroadCastReceiver to fire
    private final Object mGoogleReferrerLibWaitObject;

    // Google Install Referrer Client
    private InstallReferrerClient mInstallReferrerClient;

    TuneFirstRunLogic() {
        mGoogleReferrerLibWaitObject = new Object();
    }

    /**
     * Indicate that an Advertising ID was received.
     */
    void receivedAdvertisingId() {
        gotAdvertisingId = true;
        tryNotifyWaitObject();
    }

    /**
     * Indicate that the Google Install Referrer sequence has completed.
     */
    void googleInstallReferrerSequenceComplete() {
        gotGoogleReferrer = true;
        tryNotifyWaitObject();
    }

    private void tryNotifyWaitObject() {
        synchronized (mGoogleReferrerLibWaitObject) {
            if (gotGoogleReferrer && gotAdvertisingId) {
                mGoogleReferrerLibWaitObject.notifyAll();
                TuneDebugLog.d("FirstRun::COMPLETE");
            }
        }
    }

    /**
     * Wait for First Run Data
     * @param context Context
     * @param timeToWait Number of milliseconds to wait
     */
    void waitForFirstRunData(Context context, int timeToWait) {
        // Start the Google API to get referrer information.
        // If it succeeds, it will unblock the BroadcastReceiver wait.
        // If it fails, we want to fallback to waiting for a BroadcastReceiver.
        TuneDebugLog.d("FirstRun::waitForFirstRunData(START)");
        startInstallReferrerClientConnection(context);

        // Wait for the Broadcast Receiver to unblock
        synchronized (mGoogleReferrerLibWaitObject) {
            try {
                mGoogleReferrerLibWaitObject.wait(timeToWait);
            } catch (InterruptedException e) {
                TuneDebugLog.w("FirstRun::waitForFirstRunData() interrupted", e);
            }
        }

        TuneDebugLog.d("FirstRun::waitForFirstRunData(COMPLETE)");
    }

    /**
     * @return True if the FirstRun logic is waiting for more input
     */
    boolean isWaiting() {
        return !gotGoogleReferrer || !gotAdvertisingId;
    }

    // Cancel waiting for First Run Data
    void cancel() {
        synchronized (mGoogleReferrerLibWaitObject) {
            mGoogleReferrerLibWaitObject.notifyAll();
        }
    }

    private void startInstallReferrerClientConnection(Context context) {
        mInstallReferrerClient = InstallReferrerClient.newBuilder(context).build();

        try {
            mInstallReferrerClient.startConnection(mReferrerStateListener);

            // Start a timeout handler for the case where the callback is never called.
            Handler timeoutHandler = new Handler(Looper.getMainLooper());
            timeoutHandler.postDelayed(new Runnable() {
                public void run() {
                    if (!gotGoogleReferrer) {
                        TuneDebugLog.d("FirstRun::Install Referrer Service Callback Timeout");
                        safelyEndConnection();
                        googleInstallReferrerSequenceComplete();
                    }
                }
            }, InstallReferrerClientConnectionTimeout);
        } catch (Exception e) {
            // We have observed a "SecurityException" on a few devices.  Just to be safe, catch everything.
            // This error is generated by startConnection, which never started, so we don't need to end it either.
            TuneDebugLog.e("FirstRun::Exception", e);
            onInstallReferrerResponseError(InstallReferrerResponse_GeneralException);
        }
    }

    private InstallReferrerStateListener mReferrerStateListener = new InstallReferrerStateListener() {
        @Override
        public void onInstallReferrerSetupFinished(int responseCode) {
            // Note that this callback blocks the Main UI thread until complete.
            TuneDebugLog.d("FirstRun::onInstallReferrerSetupFinished() CODE: " + responseCode);
            if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                onInstallReferrerResponseOK();
            } else {
                safelyEndConnection();
                onInstallReferrerResponseError(responseCode);
            }
        }

        @Override
        public void onInstallReferrerServiceDisconnected() {
            TuneDebugLog.d("FirstRun::onInstallReferrerServiceDisconnected()");
            // "This does not remove install referrer service connection itself - this binding
            // to the service will remain active, and you will receive a call to onInstallReferrerSetupFinished(int)
            // when install referrer service is next running and setup is complete."
            // https://developer.android.com/reference/com/android/installreferrer/api/InstallReferrerStateListener.html#oninstallreferrerservicedisconnected
        }
    };

    private void safelyEndConnection() {
        if (mInstallReferrerClient.isReady()) {
            mInstallReferrerClient.endConnection();
        }
    }

    private void onInstallReferrerResponseOK() {
        TuneDebugLog.d("FirstRun::onInstallReferrerResponseOK()");
        try {
            ReferrerDetails details = mInstallReferrerClient.getInstallReferrer();
            if (details != null) {
                TuneDebugLog.d("FirstRun::Install Referrer: " + details.getInstallReferrer());

                Tune.getInstance().setInstallReferrer(details.getInstallReferrer());

                long installBeginTimestamp = details.getInstallBeginTimestampSeconds();
                if (installBeginTimestamp != 0) {
                    TuneInternal.getInstance().getTuneParams().setInstallBeginTimestampSeconds(details.getInstallBeginTimestampSeconds());
                }

                long referrerClickTimestamp = details.getReferrerClickTimestampSeconds();
                if (referrerClickTimestamp != 0) {
                    TuneInternal.getInstance().getTuneParams().setReferrerClickTimestampSeconds(referrerClickTimestamp);
                }

                TuneDebugLog.d("FirstRun::Install Referrer Timestamps: [" + referrerClickTimestamp + "," + installBeginTimestamp + "]");
            }
        } catch (Exception e) {
            // While a RemoteException needs to be caught per the API signature, we have also observed an "IllegalStateException" from a customer, thus we catch a generic Exception.
            TuneDebugLog.e("FirstRun::ReferrerDetails exception", e);
        }

        safelyEndConnection();
        googleInstallReferrerSequenceComplete();
    }

    /**
     * Indicate that an error occurred.
     * Error code descriptions:
     * <ul>
     *     <li>SERVICE_UNAVAILABLE -- Was not possible to connect to the Google Play app service. Maybe it is updating or it's not present on current device.</li>
     *     <li>FEATURE_NOT_SUPPORTED -- Install referrer API not available on current device.  Try checking the broadcast.</li>
     *     <li>DEVELOPER_ERROR -- Error caused by incorrect usage. E.g: Already connecting to the service or Client was already closed and can't be reused.</li>
     * </ul>
     * @param responseCode Response Code
     */
    void onInstallReferrerResponseError(int responseCode) {
        // Implementation Note:  It doesn't really matter what the error is other than for logging purposes.
        TuneDebugLog.d("FirstRun::onInstallReferrerResponseError(" + responseCode + ")");
        googleInstallReferrerSequenceComplete();
    }
}
