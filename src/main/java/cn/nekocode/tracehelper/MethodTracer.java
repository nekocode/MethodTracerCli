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

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import org.apache.commons.cli.*;

import java.util.Scanner;

/**
 * @author nekocode (nekocode.cn@gmail.com)
 */
public class MethodTracer {

    public static void main(String[] args) throws InterruptedException {
        Option optionAdbPath = Option.builder("a")
                .argName("adb_path")
                .hasArg()
                .desc("Path of adb")
                .build();

        Option optionDeviceNumber = Option.builder("e")
                .argName("device-serial")
                .hasArg()
                .desc("Serial number of connected traget device")
                .build();

        Option optionPortNumber = Option.builder("p")
                .argName("port")
                .hasArg()
                .desc("Perfd prot number (default is 12389)")
                .build();

        Option optionSamplingInterval = Option.builder("i")
                .argName("sampling-interval")
                .hasArg()
                .desc("The sampling interval of method tracing (can ben 0, default is 10)")
                .build();

        Option optionTime = Option.builder("t")
                .argName("N")
                .hasArg()
                .desc("Trace application for N seconds")
                .build();

        Option optionOut = Option.builder("o")
                .argName("out-file")
                .hasArg()
                .desc("Output file name (default is \"out.trace\")")
                .build();

        Option optionHelp = Option.builder("h")
                .longOpt("help")
                .desc("Show this help information")
                .build();

        Options options = new Options();
        options.addOption(optionAdbPath);
        options.addOption(optionDeviceNumber);
        options.addOption(optionPortNumber);
        options.addOption(optionSamplingInterval);
        options.addOption(optionTime);
        options.addOption(optionOut);
        options.addOption(optionHelp);

        try {
            String value;
            CommandLine commandLine = new DefaultParser().parse(options, args);
            if (commandLine.hasOption(optionHelp.getOpt())) {
                showHelp(options);
                return;
            }

            if (commandLine.getArgs().length != 1) {
                throw new ParseException("Missing argument: <app-name>");
            }

            String packageName = commandLine.getArgs()[0];
            String adbPath = commandLine.getOptionValue(optionAdbPath.getOpt());
            String deviceNumber = commandLine.getOptionValue(optionDeviceNumber.getOpt());

            value = commandLine.getOptionValue(optionPortNumber.getOpt());
            int perfdPort = value == null ? 12389 : Integer.valueOf(value);

            value = commandLine.getOptionValue(optionSamplingInterval.getOpt());
            int samplingInterval = value == null ? 10 : Integer.valueOf(value);

            value = commandLine.getOptionValue(optionTime.getOpt());
            int time = value == null ? 0 : Integer.valueOf(value);

            value = commandLine.getOptionValue(optionOut.getOpt());
            String outputFile = value == null ? "out.trace" : value;

            trace(adbPath, deviceNumber, packageName, perfdPort, samplingInterval, time, outputFile);

        } catch (ParseException exception) {
            System.out.println("Syntax error: " + exception.getMessage());
            showHelp(options);
        }
    }

    private static void showHelp(Options options) {
        new HelpFormatter().printHelp("MethodTracer.jar <app-name>",
                "A command-line interface of android stuido's method tracer", options, null, true);
    }

    private static void trace(String adbPath, String deviceNumber, String packageName, int perfdPort,
                              int samplingInterval, int time, String outputFile) throws InterruptedException {
        AndroidDebugBridge.initIfNeeded(true);
        AndroidDebugBridge adb;
        if (adbPath != null) {
            adb = AndroidDebugBridge.createBridge(adbPath, false);
        } else {
            adb = AndroidDebugBridge.createBridge();
        }

        StudioProfilerHack profiler = null;
        try {
            int i;
            for (i = 0; i < 10; i++) {
                Thread.sleep(100);
                if (adb.isConnected()) {
                    break;
                }
            }

            if (!adb.isConnected()) {
                System.out.println("Couldn't connect to ADB server");
                return;
            }

            for (i = 0; i < 10; i++) {
                Thread.sleep(100);
                if (adb.hasInitialDeviceList()) {
                    break;
                }
            }

            if (!adb.hasInitialDeviceList() || adb.getDevices().length == 0) {
                System.out.println("No connected devices");
                return;
            }

            IDevice device = null;
            if (deviceNumber == null) {
                device = adb.getDevices()[0];
            } else {
                for (IDevice d : adb.getDevices()) {
                    if (deviceNumber.equals(d.getSerialNumber())) {
                        device = d;
                        break;
                    }
                }

                if (device == null) {
                    System.out.println("Device:" + deviceNumber + " not found.");
                    return;
                }
            }

            profiler = new StudioProfilerHack(
                    device, packageName, perfdPort, samplingInterval, outputFile);

            if (profiler.startProfilingApp()) {
                System.out.println("Start profiling...");

                if (time <= 0) {
                    System.out.println("Enter to stop profiling");
                    new Scanner(System.in).nextLine();
                } else {
                    Thread.sleep(time * 1000);
                }

                if (profiler.stopProfilingApp()) {
                    System.out.println("Stop profiling success. The trace file has been saved to \"" + outputFile + "\"");
                } else {
                    System.out.println("Stop profiling failed.");
                }
            } else {
                System.out.println("Start profiling failed.");
            }

        } catch (Exception e) {
            e.printStackTrace();

        } finally {
            if (profiler != null) {
                profiler.terminate();
            }
            AndroidDebugBridge.terminate();
        }
    }
}
