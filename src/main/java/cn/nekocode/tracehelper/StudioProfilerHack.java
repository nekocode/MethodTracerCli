/*
 * Copyright 2017 nekocode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.nekocode.tracehelper;

import com.android.ddmlib.*;
import com.android.tools.profiler.proto.Agent;
import org.apache.commons.io.Charsets;

import java.io.*;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Part of the code was copied from the android-plugin in Android Studio
 *
 * @author nekocode (nekocode.cn@gmail.com)
 */
public class StudioProfilerHack implements ClientData.IMethodProfilingHandler {
    private final IDevice mDevice;
    private final String mPackageName;
    private final int mPerfdPort;
    private final int mSamplingInterval;
    private final String mOutputFile;
    private int mLocalPort;
    private Thread mPerfdThread;
    private boolean mIsProfilingFinished = false;


    public StudioProfilerHack(IDevice device, String packageName, int perfdPort,
                              int samplingInterval, String outputFile) {
        this.mDevice = device;
        this.mPackageName = packageName;
        this.mPerfdPort = perfdPort;
        this.mSamplingInterval = samplingInterval;
        this.mOutputFile = outputFile;
    }

    public boolean startProfilingApp() {
        mPerfdThread = runAndWaitForRemotePerfdThreadSuccess();
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (!mPerfdThread.isAlive()) {
            // If the remote perdf process is not running
            System.out.println("Copy and excute perdf failed.");
            return false;
        }

        try {
            mLocalPort = findAvailableSocketPort();
            mDevice.createForward(mLocalPort, mPerfdPort);
        } catch (Exception e) {
            System.out.println("Create adb forward failed.");
            return false;
        }

        Client client = mDevice.getClient(mPackageName);
        if (client == null) {
            System.out.println("Target app:" + mPackageName + " is not running.");
            return false;
        }


        if (client.getClientData().getMethodProfilingStatus() != ClientData.MethodProfilingStatus.OFF) {
            System.out.println("Start profiling failed. The app has an on-going profiling session.");
            return false;
        }

        // Start profiling
        ClientData.setMethodProfilingHandler(this);
        try {
            if(mSamplingInterval > 0) {
                client.startSamplingProfiler(mSamplingInterval, TimeUnit.MICROSECONDS);
            } else {
                client.startMethodTracer();
            }
        } catch (IOException e) {
            System.out.println("Start profiling failed.");
            return false;
        }

        return true;
    }

    public boolean stopProfilingApp() {
        Client client = mDevice.getClient(mPackageName);
        if (client == null) {
            System.out.println("Target app:" + mPackageName + " is not running.");
            return false;
        }

        if (client.getClientData().getMethodProfilingStatus() == ClientData.MethodProfilingStatus.OFF) {
            System.out.println("Stop profiling failed. The app is not being profiled.");
            return false;
        }

        // Stop profiling
        try {
            mIsProfilingFinished = false;

            if(mSamplingInterval > 0) {
                client.stopSamplingProfiler();
            } else {
                client.stopMethodTracer();
            }
        } catch (IOException e) {
            System.out.println("Stop profiling failed.");
            return false;
        }

        // Wait for finishing saving
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(100);
                if (mIsProfilingFinished) {
                    break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return true;
    }

    public void terminate() {
        if (mPerfdThread != null) {
            mPerfdThread.interrupt();
        }

        try {
            mDevice.removeForward(mLocalPort, mPerfdPort);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSuccess(String remoteFilePath, Client client) {
        System.out.println("Method profiling: Older devices (API level < 10) are not supported. Please use DDMS.");
        mIsProfilingFinished = true;
    }

    @Override
    public void onSuccess(byte[] data, Client client) {
        File traceFile = new File(mOutputFile);
        if (traceFile.exists()) {
            traceFile.delete();
        }

        OutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(traceFile);
            outputStream.write(data);

        } catch (Exception e) {
            System.out.println("Save trace data failed.");

        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                }
            }
        }

        mIsProfilingFinished = true;
    }

    @Override
    public void onStartFailure(Client client, String message) {
        System.out.println("Failed to start profiling: " + message);
        mIsProfilingFinished = true;
    }

    @Override
    public void onEndFailure(Client client, String message) {
        System.out.println("Failed to stop profiling: " + message);
        mIsProfilingFinished = true;
    }

    private Thread runAndWaitForRemotePerfdThreadSuccess() {
        RemotePerfdThread perfdThread = new RemotePerfdThread();
        perfdThread.start();

        while (!Thread.currentThread().isInterrupted() && perfdThread.isAlive()) {
            try {
                Thread.sleep(100);
                if (perfdThread.mIsSuccessed) {
                    break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                perfdThread.interrupt();
            }
        }

        return perfdThread;
    }

    private static int findAvailableSocketPort() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);

        int var2;
        try {
            int port = serverSocket.getLocalPort();
            synchronized (serverSocket) {
                try {
                    serverSocket.wait(1L);
                } catch (InterruptedException var9) {
                }
            }

            var2 = port;
        } finally {
            serverSocket.close();
        }

        return var2;
    }


    /**
     * Copy perfd to device and excute it
     */
    private class RemotePerfdThread extends Thread {
        private boolean mIsSuccessed = false;


        @Override
        public void run() {
            try {
                String deviceDir = "/data/local/tmp/perfd/";
                copyPerfdToDevice(deviceDir);
                pushAgentConfig(deviceDir);

                mDevice.executeShellCommand(deviceDir + "perfd -config_file=" + deviceDir + "agent.config",
                        new IShellOutputReceiver() {
                            @Override
                            public void addOutput(byte[] data, int offset, int length) {
                                String s = new String(data, offset, length, Charsets.UTF_8);
                                if (s.startsWith("Server listening on")) {
                                    mIsSuccessed = true;
                                } else {
                                    Thread.currentThread().interrupt();
                                }
                            }

                            @Override
                            public void flush() {
                            }

                            @Override
                            public boolean isCancelled() {
                                return false;
                            }
                        }, 0L, null);

            } catch (Exception ingred) {
                Thread.currentThread().interrupt();
            }
        }

        private void copyPerfdToDevice(String deviceDir) throws Exception {
            URL url = Thread.currentThread().getContextClassLoader().getResource("perfd");
            if (url == null) {
                return;
            }

            Iterator abisIterator = mDevice.getAbis().iterator();
            ArrayList<String> supportedPaths = new ArrayList<>();
            while (abisIterator.hasNext()) {
                supportedPaths.add(abisIterator.next() + "/perfd");
            }

            File perfd = null;

            if ("jar".equals(url.getProtocol())) {
                ZipInputStream jarInputStream = null;

                try {
                    String jarPath = url.getPath().substring(5);
                    String[] paths = jarPath.split("!");
                    FileInputStream jarFileInputStream = new FileInputStream(paths[0]);
                    jarInputStream = new ZipInputStream(jarFileInputStream);

                    ZipEntry zipEntry;
                    String entryName;
                    final InputStream in = new BufferedInputStream(jarInputStream);
                    boolean finded = false;
                    while ((zipEntry = jarInputStream.getNextEntry()) != null) {
                        entryName = zipEntry.getName();
                        if (entryName.startsWith("perfd/")) {
                            final String p = entryName.substring(6);
                            for (String path : supportedPaths) {
                                if (path.equals(p)) {
                                    finded = true;
                                    break;
                                }
                            }
                        }

                        if (finded) {
                            // Copy perd to tmp directory
                            File tmpDir = new File(System.getProperty("java.io.tmpdir"));
                            if (!tmpDir.exists()) {
                                tmpDir.mkdirs();
                            }
                            perfd = new File(tmpDir, "perfd");
                            Files.copy(in, perfd.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            break;
                        }
                    }

                } catch (Exception ignored) {
                } finally {
                    if (jarInputStream != null) {
                        try {
                            jarInputStream.close();
                        } catch (Exception ignored) {
                        }
                    }
                }

            } else if ("file".equals(url.getProtocol())) {
                for (String path : supportedPaths) {
                    perfd = new File(url.getPath(), path);
                    if (perfd.exists()) {
                        break;
                    }
                    perfd = null;
                }
            }

            if (perfd != null) {
                this.pushPerfdToDevice(perfd, deviceDir);
            } else {
                throw new Exception("Cannot find perfd.");
            }
        }

        private void pushPerfdToDevice(File file, String deviceDir)
                throws AdbCommandRejectedException, IOException {

            final String perfdFileName = "perfd";
            try {
                if (file == null) {
                    throw new RuntimeException(String.format("File %s could not be found for device: %s",
                            new Object[]{perfdFileName, mDevice}));
                } else {
                    mDevice.executeShellCommand("rm -f " + deviceDir + perfdFileName, new NullOutputReceiver());
                    mDevice.executeShellCommand("mkdir -p " + deviceDir, new NullOutputReceiver());
                    mDevice.pushFile(file.getAbsolutePath(), deviceDir + perfdFileName);

                    ChmodOutputListener chmodListener = new ChmodOutputListener();
                    mDevice.executeShellCommand("chmod +x " + deviceDir + perfdFileName, chmodListener);
                    if (chmodListener.hasErrors()) {
                        mDevice.executeShellCommand("chmod 777 " + deviceDir + perfdFileName, new NullOutputReceiver());
                    }
                }
            } catch (SyncException | ShellCommandUnresponsiveException | TimeoutException var6) {
                throw new RuntimeException(var6);
            }
        }

        private void pushAgentConfig(String devicePath) throws Exception {
            Agent.SocketType socketType = Agent.SocketType.UNSPECIFIED_SOCKET;
            Agent.AgentConfig agentConfig = Agent.AgentConfig.newBuilder()
                    .setUseJvmti(true)
                    .setMemConfig(Agent.AgentConfig.MemoryConfig.newBuilder()
                            .setUseLiveAlloc(true)
                            .setMaxStackDepth(50).build())
                    .setSocketType(socketType)
                    .setServiceAddress("127.0.0.1:" + String.valueOf(mPerfdPort))
                    .setServiceSocketName("@AndroidStudioProfiler").build();

            final String agentFileName = "agent.config";
            File configFile = new File(System.getProperty("java.io.tmpdir"), agentFileName);
            OutputStream oStream = new FileOutputStream(configFile);
            agentConfig.writeTo(oStream);
            mDevice.executeShellCommand("rm -f " + devicePath + agentFileName, new NullOutputReceiver());
            mDevice.pushFile(configFile.getAbsolutePath(), devicePath + agentFileName);
        }

        private class ChmodOutputListener implements IShellOutputReceiver {
            private static final String BAD_MODE = "Bad mode";
            private boolean myHasErrors;

            private ChmodOutputListener() {
            }

            public void addOutput(byte[] data, int offset, int length) {
                String s = new String(data, Charsets.UTF_8);
                this.myHasErrors = s.contains(BAD_MODE);
            }

            public void flush() {
            }

            public boolean isCancelled() {
                return false;
            }

            private boolean hasErrors() {
                return this.myHasErrors;
            }
        }
    }
}
