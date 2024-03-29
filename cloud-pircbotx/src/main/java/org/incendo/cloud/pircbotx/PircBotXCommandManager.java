//
// MIT License
//
// Copyright (c) 2024 Incendo
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package org.incendo.cloud.pircbotx;

import java.util.function.BiFunction;
import java.util.function.Function;
import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.CloudCapability;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.caption.Caption;
import org.incendo.cloud.caption.CaptionProvider;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.CommandExecutionException;
import org.incendo.cloud.exception.InvalidCommandSenderException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
import org.incendo.cloud.exception.NoSuchCommandException;
import org.incendo.cloud.exception.handling.ExceptionContext;
import org.incendo.cloud.exception.handling.ExceptionHandler;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.pircbotx.arguments.UserParser;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.hooks.types.GenericMessageEvent;

/**
 * Command manager implementation for PircBotX 2.0
 *
 * @param <C> command sender type
 */
public class PircBotXCommandManager<C> extends CommandManager<C> {

    private static final String MESSAGE_INTERNAL_ERROR = "An internal error occurred while attempting to perform this command.";
    private static final String MESSAGE_INVALID_SYNTAX = "Invalid Command Syntax. Correct command syntax is: ";
    private static final String MESSAGE_NO_PERMS = "I'm sorry, but you do not have permission to perform this command. "
            + "Please contact the server administrators if you believe that this is in error.";
    private static final String MESSAGE_UNKNOWN_COMMAND = "Unknown command";

    /**
     * Key used to access the generic message event from the command context.
     */
    @API(status = API.Status.STABLE)
    public static final CloudKey<GenericMessageEvent> PIRCBOTX_MESSAGE_EVENT_KEY = CloudKey.of(
            "__internal_generic_message_event__",
            GenericMessageEvent.class
    );
    /**
     * Meta key for accessing the {@link org.pircbotx.PircBotX} instance from a
     * {@link org.incendo.cloud.context.CommandContext} instance
     */
    public static final String PIRCBOTX_META_KEY = "__internal_pircbotx__";
    /**
     * Variables: {@code <input>}
     */
    public static final Caption ARGUMENT_PARSE_FAILURE_USER_KEY = Caption.of("argument.parse.failure.use");
    /**
     * Default caption for {@link #ARGUMENT_PARSE_FAILURE_USER_KEY}
     */
    public static final String ARGUMENT_PARSE_FAILURE_USER = "'<input>' is not a valid user";

    private final String commandPrefix;
    private final BiFunction<C, String, Boolean> permissionFunction;
    private final Function<User, C> userMapper;
    private final PircBotX pircBotX;

    /**
     * Create a new command manager instance
     *
     * @param pircBotX                    PircBotX instance. This is used to register the
     *                                    {@link org.pircbotx.hooks.ListenerAdapter} that will forward commands to the
     *                                    command dispatcher
     * @param commandExecutionCoordinator Execution coordinator instance. The coordinator is in charge of executing incoming
     *                                    commands. Some considerations must be made when picking a suitable execution coordinator
     *                                    for your platform. For example, an entirely asynchronous coordinator is not suitable
     *                                    when the parsers used in that particular platform are not thread safe. If you have
     *                                    commands that perform blocking operations, however, it might not be a good idea to
     *                                    use a synchronous execution coordinator. In most cases you will want to pick between
     *                                    {@link ExecutionCoordinator#simpleCoordinator()} and
     *                                    {@link ExecutionCoordinator#asyncCoordinator()}
     * @param commandRegistrationHandler  Command registration handler. This will get called every time a new command is
     *                                    registered to the command manager. This may be used to forward command registration
     * @param permissionFunction          Function used to determine whether or not a sender is permitted to use a certain
     *                                    command. The first input is the sender of the command, and the second parameter is
     *                                    the the command permission string. The return value should be {@code true} if the
     *                                    sender is permitted to use the command, else {@code false}
     * @param userMapper                  Function that maps {@link User users} to the custom command sender type
     * @param commandPrefix               The prefix that must be applied to all commands for the command to be valid
     */
    public PircBotXCommandManager(
            final @NonNull PircBotX pircBotX,
            final @NonNull ExecutionCoordinator<C> commandExecutionCoordinator,
            final @NonNull CommandRegistrationHandler<C> commandRegistrationHandler,
            final @NonNull BiFunction<C, String, Boolean> permissionFunction,
            final @NonNull Function<User, C> userMapper,
            final @NonNull String commandPrefix
    ) {
        super(commandExecutionCoordinator, commandRegistrationHandler);
        this.pircBotX = pircBotX;
        this.permissionFunction = permissionFunction;
        this.commandPrefix = commandPrefix;
        this.userMapper = userMapper;
        this.pircBotX.getConfiguration().getListenerManager().addListener(new CloudListenerAdapter<>(this));
        this.captionRegistry().registerProvider(
                CaptionProvider.constantProvider(ARGUMENT_PARSE_FAILURE_USER_KEY, ARGUMENT_PARSE_FAILURE_USER)
        );
        this.registerCommandPreProcessor(context -> context.commandContext().store(PIRCBOTX_META_KEY, pircBotX));
        this.parserRegistry().registerParser(UserParser.userParser());

        // No "native" command system means that we can delete commands just fine.
        this.registerCapability(CloudCapability.StandardCapabilities.ROOT_COMMAND_DELETION);

        this.registerDefaultExceptionHandlers();
    }

    @Override
    public final boolean hasPermission(
            final @NonNull C sender,
            final @NonNull String permission
    ) {
        return this.permissionFunction.apply(sender, permission);
    }

    /**
     * Get the command prefix. A message should be classed as a command if, and only if, it is prefixed
     * with this prefix
     *
     * @return Command prefix
     */
    public final @NonNull String getCommandPrefix() {
        return this.commandPrefix;
    }

    final @NonNull Function<User, C> getUserMapper() {
        return this.userMapper;
    }

    private void registerDefaultExceptionHandlers() {
        this.registerHandler(Throwable.class, (event, throwable) -> event.respondWith(throwable.getMessage()));
        this.registerHandler(CommandExecutionException.class, (event, throwable) -> {
                event.respondWith(MESSAGE_INTERNAL_ERROR);
                throwable.getCause().printStackTrace();
        });
        this.registerHandler(ArgumentParseException.class, (event, throwable) ->
                event.respondWith("Invalid Command Argument" + throwable.getCause().getMessage())
        );
        this.registerHandler(NoSuchCommandException.class, (event, throwable) -> event.respondWith(MESSAGE_UNKNOWN_COMMAND));
        this.registerHandler(NoPermissionException.class, (event, throwable) -> event.respondWith(MESSAGE_NO_PERMS));
        this.registerHandler(InvalidCommandSenderException.class, (event, throwable) ->
                event.respondWith(throwable.getMessage())
        );
        this.registerHandler(InvalidSyntaxException.class, (event, throwable) ->
                event.respondWith(MESSAGE_INVALID_SYNTAX + this.getCommandPrefix() + throwable.correctSyntax())
        );
    }

    private <T extends Throwable> void registerHandler(
            final @NonNull Class<T> exceptionType,
            final @NonNull PircBotXExceptionHandler<C, T> handler
    ) {
        this.exceptionController().registerHandler(exceptionType, handler);
    }


    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    private interface PircBotXExceptionHandler<C, T extends Throwable> extends ExceptionHandler<C, T> {

        @Override
        default void handle(@NonNull ExceptionContext<C, T> context) throws Throwable {
            final GenericMessageEvent event = context.context().get(PIRCBOTX_MESSAGE_EVENT_KEY);
            this.handle(event, context.exception());
        }

        void handle(@NonNull GenericMessageEvent event, @NonNull T throwable);
    }
}
