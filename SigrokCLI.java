/*
 * This file is part of the sigrok-cli-java project.
 * 
 * Copyright (C) 2014 Martin Ling <martin-sigrok@earth.li>
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import org.sigrok.core.interfaces.DatafeedCallback;
import org.sigrok.core.classes.*;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import static net.sourceforge.argparse4j.impl.Arguments.storeTrue;

import java.util.*;

class CLIDatafeedCallback implements DatafeedCallback
{
    Output output;

    public CLIDatafeedCallback(Output output)
    {
        this.output = output;
    }

    public void run(Device device, Packet packet)
    {
        String text = output.receive(packet);
        if (text.length() > 0)
            System.out.print(text);
    }
}

class SigrokCLI
{
    static final String VERSION = "0.1";

    /* Helper function to print a one-line description of a device. */
    static void print_device_info(HardwareDevice device)
    {
        System.out.printf("%s -", device.driver().name());
        String[] parts =
            {device.vendor(), device.model(), device.version()};
        for (String part : parts)
            if (part.length() > 0)
                System.out.printf(" %s", part);
        Vector<Channel> channels = device.channels();
        System.out.printf(" with %d channels:", channels.size());
        for (Channel channel : channels)
            System.out.printf(" %s", channel.name());
        System.out.println();
    }

    /* Helpers for checking and obtaining command line arguments. */

    static Map<String,Object> args = null;

    static boolean arg_given(String name)
    {
        Object value = args.get(name);
        if (value instanceof String)
            return ((String) value).length() > 0;
        else if (value instanceof Boolean)
            return (Boolean) value;
        else
            return (value != null);
    }

    static String arg_string(String name)
    {
        return (String) args.get(name);
    }

    static int arg_int(String name)
    {
        return (Integer) args.get(name);
    }

    public static void main(String argv[])
    {
        ArgumentParser parser = ArgumentParsers.newArgumentParser("SigrokCLI");

        /* Set up command line options. */
        parser.addArgument("-V", "--version")
            .help("Show version").action(storeTrue());
        parser.addArgument("-l", "--loglevel")
            .help("Set log level").type(Integer.class);
        parser.addArgument("-d", "--driver")
            .help("The driver to use");
        parser.addArgument("-c", "--config")
            .help("Specify device configuration options");
        parser.addArgument("-i", "--input-file")
            .help("Load input from file").dest("input_file");
        parser.addArgument("-I", "--input-format")
            .help("Input format");
        parser.addArgument("-O", "--output-format")
            .help("Output format").setDefault("bits");
        parser.addArgument("-p", "--channels")
            .help("Channels to use");
        parser.addArgument("-g", "--channel-group")
            .help("Channel group to use");
        parser.addArgument("--scan")
            .help("Scan for devices").action(storeTrue());
        parser.addArgument("--time")
            .help("How long to sample (ms)");
        parser.addArgument("--samples")
            .help("Number of samples to acquire");
        parser.addArgument("--frames")
            .help("Number of frames to acquire");
        parser.addArgument("--continuous")
            .help("Sample continuously").action(storeTrue());
        parser.addArgument("--set")
            .help("Set device options only").action(storeTrue());

        /* Parse command line options. */
        try
        {
            args = parser.parseArgs(argv).getAttrs();
        }
        catch (ArgumentParserException e)
        {
            System.out.println(e.toString());
            System.exit(1);
        }

        /* Check for valid combination of arguments to proceed. */
        if (!(arg_given("version")
            || arg_given("scan")
            || (arg_given("driver") && (
                arg_given("set") ||
                arg_given("time") ||
                arg_given("samples") ||
                arg_given("frames") ||
                arg_given("continuous")))
            || arg_given("input_file")))
        {
            parser.printHelp();
            System.exit(1);
        }

        Context context = Context.create();

        if (arg_given("version"))
        {
            /* Display version information. */
            System.out.printf("SigrokCLI %s", VERSION);
            System.out.printf("\nUsing libsigrok %s (lib version %s).",
                context.package_version(),
                context.lib_version());
            System.out.printf("\nSupported hardware drivers:\n");
            for (Driver driver : context.drivers().values())
            {
                System.out.printf("  %-20s %s\n",
                    driver.name(),
                    driver.long_name());
            }
            System.out.printf("\nSupported input formats:\n");
            for (InputFormat input : context.input_formats().values())
            {
                System.out.printf("  %-20s %s\n",
                    input.name(),
                    input.description());
            }
            System.out.printf("\nSupported output formats:\n");
            for (OutputFormat output : context.output_formats().values())
            {
                System.out.printf("  %-20s %s\n",
                    output.name(),
                    output.description());
            }
            System.out.println();
            System.exit(0);
        }

        if (arg_given("loglevel"))
            context.set_log_level(LogLevel.get(arg_int("loglevel")));

        if (arg_given("scan") && !arg_given("driver"))
        {
            /* Scan for devices using all drivers. */
            for (Driver driver : context.drivers().values())
            {
                for (HardwareDevice device : driver.scan())
                    print_device_info(device);
            }
            System.exit(0);
        }

        Device device = null;

        if (arg_given("input_file"))
        {
            device = context.open_file(arg_string("input_file")).device();
        }
        else if (arg_given("driver"))
        {
            /* Separate driver name and configuration options. */
            String[] driver_spec = (arg_string("driver")).split(":");

            /* Use specified driver. */
            Driver driver = context.drivers().get(driver_spec[0]);

            /* Parse key=value configuration pairs. */
            String[] driver_pairs = Arrays.copyOfRange(
                driver_spec, 1, driver_spec.length);
            Map<ConfigKey, Variant> scan_options =
              new HashMap<ConfigKey, Variant>();
            for (String pair : driver_pairs)
            {
                String[] parts = pair.split("=");
                String name = parts[0], value = parts[1];
                ConfigKey key = ConfigKey.get_by_identifier(name);
                scan_options.put(key, key.parse_string(value));
            }

            /* Scan for devices. */
            Vector<HardwareDevice> devices = driver.scan(scan_options);

            if (arg_given("scan"))
            {
                /* Scan requested only. */
                for (HardwareDevice scan_device : devices)
                    print_device_info(scan_device);
                System.exit(0);
            }

            /* Use first device found. */
            device = devices.get(0);

            /* Apply device settings from command line. */
            Map<ConfigKey, String> options = new HashMap<ConfigKey, String>();
            options.put(ConfigKey.getLIMIT_MSEC(), "time");
            options.put(ConfigKey.getLIMIT_SAMPLES(), "samples");
            options.put(ConfigKey.getLIMIT_FRAMES(), "frames");
            for (Map.Entry<ConfigKey, String> option : options.entrySet())
            {
                ConfigKey key = option.getKey();
                String name = option.getValue();
                if (arg_given(name))
                {
                    String value = arg_string(name);
                    device.config_set(key, key.parse_string(value));
                }
            }

            if (arg_given("config"))
            {
                /* Split into key=value pairs. */
                String[] config_pairs = (arg_string("config")).split(":");

                /* Parse and apply key=value configuration pairs. */
                for (String pair : config_pairs)
                {
                    String[] parts = pair.split("=");
                    String name = parts[0], value = parts[1];
                    ConfigKey key = ConfigKey.get_by_identifier(name);
                    device.config_set(key, key.parse_string(value));
                }
            }
        }

        if (arg_given("channels"))
        {
            /* Enable selected channels only. */
            String[] enabled = (arg_string("channels")).split(",");
            Set<String> enabled_set = new HashSet<String>(Arrays.asList(enabled));
            for (Channel channel : device.channels())
                channel.set_enabled(enabled_set.contains(channel.name()));
        }

        if (arg_given("set"))
        {
            /* Exit having applied configuration settings. */
            device.close();
            System.exit(0);
        }

        /* Create session and add device. */
        Session session = context.create_session();
        session.add_device(device);

        /* Create output. */
        OutputFormat output_format =
            context.output_formats().get(arg_string("output_format"));
        Output output = output_format.create_output(device);

        /* Add datafeed callback. */
        session.add_datafeed_callback(new CLIDatafeedCallback(output));

        /* Start capture. */
        session.start();

        /* Run event loop. */
        session.run();

        /* Close device. */
        device.close();

        System.exit(0);
    }
}
