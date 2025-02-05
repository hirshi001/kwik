/*
 * Copyright © 2019, 2020, 2021, 2022, 2023, 2024 Peter Doornbosch
 *
 * This file is part of Kwik, an implementation of the QUIC protocol in Java.
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.quic.crypto;

import java.security.SecureRandom;
import net.luminis.quic.common.EncryptionLevel;
import net.luminis.quic.impl.Role;
import net.luminis.quic.impl.TransportError;
import net.luminis.quic.impl.Version;
import net.luminis.quic.impl.VersionHolder;
import net.luminis.quic.frame.CryptoFrame;
import net.luminis.quic.frame.QuicFrame;
import net.luminis.quic.log.Logger;
import net.luminis.quic.send.Sender;
import net.luminis.quic.tls.CertificateMessageBuilder;
import net.luminis.quic.tls.ClientHelloBuilder;
import net.luminis.tls.ProtectionKeysType;
import net.luminis.tls.TlsConstants;
import net.luminis.tls.TlsProtocolException;
import net.luminis.tls.engine.ClientMessageSender;
import net.luminis.tls.engine.TlsClientEngine;
import net.luminis.tls.engine.TlsMessageParser;
import net.luminis.tls.engine.TlsServerEngine;
import net.luminis.tls.engine.TlsStatusEventHandler;
import net.luminis.tls.engine.impl.TlsClientEngineImpl;
import net.luminis.tls.handshake.CertificateMessage;
import net.luminis.tls.handshake.ClientHello;
import net.luminis.tls.handshake.FinishedMessage;
import net.luminis.tls.handshake.HandshakeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.luminis.quic.test.FieldSetter.setField;
import static net.luminis.tls.TlsConstants.HandshakeType.certificate_request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CryptoStreamTest {

    public static final Version QUIC_VERSION = Version.getDefault();

    private CryptoStream cryptoStream;
    private TlsMessageParser messageParser;
    private Sender sender;

    @BeforeEach
    void prepareObjectUnderTest() throws Exception {
        sender = mock(Sender.class);
        cryptoStream = new CryptoStream(new VersionHolder(QUIC_VERSION), EncryptionLevel.Handshake, null,
                Role.Client, new TlsClientEngineImpl(mock(ClientMessageSender.class), mock(TlsStatusEventHandler.class)), mock(Logger.class), sender);
        messageParser = mock(TlsMessageParser.class);
        setField(cryptoStream, cryptoStream.getClass().getDeclaredField("tlsMessageParser"), messageParser);

        setParseFunction(buffer -> {
            buffer.mark();
            int type = buffer.get();
            buffer.reset();
            int length = buffer.getInt() & 0x00ffffff;
            byte[] stringBytes = new byte[length];
            buffer.get(stringBytes);
            return new MockTlsMessage(type, new String(stringBytes));
        });
    }

    @Test
    void parseSingleMessageInSingleFrame() throws Exception {
        cryptoStream.add(new CryptoFrame(QUIC_VERSION, convertToMsgBytes(13, "first crypto frame")));

        assertThat(cryptoStream.getTlsMessages())
                .isNotEmpty()
                .contains(new MockTlsMessage("first crypto frame"));
        assertThat(((MockTlsMessage) cryptoStream.getTlsMessages().get(0)).getType()).isEqualTo(certificate_request);
    }

    @Test
    void parserWaitsForAllFramesNeededToParseWholeMessage() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("first frame second frame last crypto frame");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 12)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 16, "second frame ".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 29, "last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("first frame second frame last crypto frame"));
    }

    @Test
    void parserWaitsForAllOutOfOrderFramesNeededToParseWholeMessage() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("first frame second frame last crypto frame");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 29, "last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 12)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 16, "second frame ".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("first frame second frame last crypto frame"));
    }

    @Test
    void handleRetransmittedFramesWithDifferentSegmentation() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("first frame second frame last crypto frame");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 29, "last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 12)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        // Simulate second frame is never received, but all crypto content is retransmitted in different frames.
        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOf(rawMessageBytes,4 + 19)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 23, "frame last crypto frame".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("first frame second frame last crypto frame"));
    }

    @Test
    void handleOverlappingFrames() throws Exception {
        byte[] rawMessageBytes = convertToMsgBytes("abcdefghijklmnopqrstuvwxyz");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 2, "cdefghijk".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 4, "efghi".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 12, "mn".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 10, "klmnop".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOfRange(rawMessageBytes, 0, 8)));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 8, "ijklmnopqrstuvwxyz".getBytes()));

        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void parseMultipleMessages() throws Exception {
        byte[] rawMessageBytes1 = convertToMsgBytes("abcdefghijklmnopqrstuvwxyz");
        byte[] rawMessageBytes2 = convertToMsgBytes("0123456789");

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 26, rawMessageBytes2));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 8, "ijklmnopqrstuvwxyz".getBytes()));
        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 4 + 10, "klmnopqrstuvwxyz".getBytes()));
        assertThat(cryptoStream.getTlsMessages()).isEmpty();

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOfRange(rawMessageBytes1, 0, 18)));

        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcdefghijklmnopqrstuvwxyz"));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("0123456789"));
    }

    @Test
    void parseMessageSplitAccrossMultipleFrames() throws Exception {
        byte[] rawMessageBytes = new byte[4 + 5 + 4 + 5];
        System.arraycopy(convertToMsgBytes("abcde"), 0, rawMessageBytes, 0, 4 + 5);
        System.arraycopy(convertToMsgBytes("12345"), 0, rawMessageBytes, 4 + 5, 4 + 5);

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 0, Arrays.copyOfRange(rawMessageBytes, 0, 11)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(1);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 11, Arrays.copyOfRange(rawMessageBytes, 11, 12)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(1);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 12, Arrays.copyOfRange(rawMessageBytes, 12, 14)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(1);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));

        cryptoStream.add(new CryptoFrame(QUIC_VERSION, 14, Arrays.copyOfRange(rawMessageBytes, 14, 18)));
        assertThat(cryptoStream.getTlsMessages().size()).isEqualTo(2);
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("abcde"));
        assertThat(cryptoStream.getTlsMessages()).contains(new MockTlsMessage("12345"));
    }

    @Test
    void writingDataToStreamLeadsToCallingSenderWithSendFrameCallback() {
        // Given
        byte[] dataToSend = new byte[120];

        // When
        cryptoStream.write(dataToSend);

        // Then
        ArgumentCaptor<Function<Integer, QuicFrame>> captor = ArgumentCaptor.forClass(Function.class);
        verify(sender).send(captor.capture(), anyInt(), any(EncryptionLevel.class), any(Consumer.class));
        Function<Integer, QuicFrame> frameGeneratorFunction = captor.getValue();

        QuicFrame frameToSend = frameGeneratorFunction.apply(1000);
        assertThat(frameToSend).isInstanceOf(CryptoFrame.class);
        assertThat(((CryptoFrame) frameToSend).getStreamData()).hasSize(120);
        assertThat(((CryptoFrame) frameToSend).getOffset()).isEqualTo(0);
    }

    @Test
    void writingDataThatDoesNotFitInFrameLeadsToMultipleCallbacks() {
        // Given
        byte[] dataToSend = new byte[1800];
        new SecureRandom().nextBytes(dataToSend);

        // When
        cryptoStream.write(dataToSend);

        // Then
        ByteBuffer dataReceived = ByteBuffer.allocate(1800);
        while (true) {
            ArgumentCaptor<Function<Integer, QuicFrame>> captor = ArgumentCaptor.forClass(Function.class);
            verify(sender, atMost(99)).send(captor.capture(), anyInt(), any(EncryptionLevel.class), any(Consumer.class));
            List<Function<Integer, QuicFrame>> frameGeneratorFunctions = captor.getAllValues();
            clearInvocations(sender);

            if (frameGeneratorFunctions.size() == 0) {
                break;
            }

            frameGeneratorFunctions.stream().forEach(f -> {
                QuicFrame frameToSend = f.apply(1000);
                assertThat(frameToSend).isInstanceOf(CryptoFrame.class);
                assertThat(((CryptoFrame) frameToSend).getFrameLength()).isLessThanOrEqualTo(1000);
                dataReceived.put(((CryptoFrame) frameToSend).getStreamData());
            });
        }

        assertThat(dataReceived.array()).isEqualTo(dataToSend);
    }

    @Test
    void dataInMultipleWritesIsConcatenatedIntoStream() {
        // Given
        byte[] dataToSend = new byte[1800];
        new SecureRandom().nextBytes(dataToSend);

        // When
        cryptoStream.write(Arrays.copyOfRange(dataToSend, 0, 200));
        cryptoStream.write(Arrays.copyOfRange(dataToSend, 200, 1413));
        cryptoStream.write(Arrays.copyOfRange(dataToSend, 1413, 1509));
        cryptoStream.write(Arrays.copyOfRange(dataToSend, 1509, 1628));
        cryptoStream.write(Arrays.copyOfRange(dataToSend, 1628, 1800));


        // Then
        ByteBuffer dataReceived = ByteBuffer.allocate(1800);
        while (true) {
            ArgumentCaptor<Function<Integer, QuicFrame>> captor = ArgumentCaptor.forClass(Function.class);
            verify(sender, atMost(99)).send(captor.capture(), anyInt(), any(EncryptionLevel.class), any(Consumer.class));
            List<Function<Integer, QuicFrame>> frameGeneratorFunctions = captor.getAllValues();
            clearInvocations(sender);

            if (frameGeneratorFunctions.size() == 0) {
                break;
            }

            frameGeneratorFunctions.stream().forEach(f -> {
                QuicFrame frameToSend = f.apply(1000);
                if (frameToSend != null) {
                    assertThat(frameToSend).isInstanceOf(CryptoFrame.class);
                    dataReceived.put(((CryptoFrame) frameToSend).getStreamData());
                }
            });
        }

        assertThat(dataReceived.array()).isEqualTo(dataToSend);
    }

    @Test
    void veryLargeClientHelloIsRefused() throws Exception {
        // Given
        cryptoStream = new CryptoStream(new VersionHolder(QUIC_VERSION), EncryptionLevel.Initial, null,
                Role.Server, mock(TlsServerEngine.class), mock(Logger.class), mock(Sender.class));
        int extensionLength = 3000;
        String fakeExtensionType = "fa7e";
        String veryLargeExtension = fakeExtensionType + String.format("%04x", extensionLength) + "00".repeat(extensionLength);
        byte[] ch = new ClientHelloBuilder().withExtension(veryLargeExtension).buildBinary();

        // When
        assertThatThrownBy(() ->
                // When
                withSplittedMessage(ch, 1183, (offset, part) -> {
                    try {
                        cryptoStream.add(new CryptoFrame(QUIC_VERSION, offset, part));
                    }
                    catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }))
                // Then
                .hasCauseInstanceOf(TlsProtocolException.class);
    }

    @Test
    void largeNumberOfOutOfOrderCryptoFramesIsRefused() throws Exception {
        // Given
        cryptoStream = new CryptoStream(new VersionHolder(QUIC_VERSION), EncryptionLevel.Initial, null,
                Role.Server, mock(TlsServerEngine.class), mock(Logger.class), mock(Sender.class));
        int extensionLength = 4000;
        String fakeExtensionType = "fa7e";
        String veryLargeExtension = fakeExtensionType + String.format("%04x", extensionLength) + "00".repeat(extensionLength);
        byte[] ch = new ClientHelloBuilder().withExtension(veryLargeExtension).buildBinary();

        // When
        assertThatThrownBy(() ->
                // When
                withSplittedMessage(ch, 1183, (offset, part) -> {
                    try {
                        if (offset != 0) {
                            // Intentionally skipping first frame, so all frames are out of order
                            cryptoStream.add(new CryptoFrame(QUIC_VERSION, offset, part));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }))
                // Then
                .hasCauseInstanceOf(TransportError.class);
    }

    @Test
    void largeStreamOffsetIsAcceptedWhenMaximumMessageSizeIsNotExceeded() throws Exception {
        // Given
        cryptoStream = new CryptoStream(new VersionHolder(QUIC_VERSION), EncryptionLevel.Initial, null,
                Role.Server, mock(TlsServerEngine.class), mock(Logger.class), mock(Sender.class));
        int extensionLength = 2828;
        String fakeExtensionType = "fa7e";
        String largeExtension = fakeExtensionType + String.format("%04x", extensionLength) + "00".repeat(extensionLength);
        byte[] ch1 = new ClientHelloBuilder().withExtension(largeExtension).buildBinary();
        byte[] ch2 = new ClientHelloBuilder().withExtension(largeExtension).buildBinary();
        byte[] data = new byte[ch1.length + ch2.length];
        System.arraycopy(ch1, 0, data, 0, ch1.length);
        System.arraycopy(ch2, 0, data, ch1.length, ch2.length);

        // When
        withSplittedMessage(data, 1183, (offset, part) -> {
            try {
                cryptoStream.add(new CryptoFrame(QUIC_VERSION, offset, part));
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Then
        assertThat(cryptoStream.getTlsMessages())
                .isNotEmpty()
                .hasSize(2)
                .hasOnlyElementsOfType(ClientHello.class);
    }


    @Test
    void clientHelloWithNormalSizeIsAccepted() throws Exception {
        // Given
        cryptoStream = new CryptoStream(new VersionHolder(QUIC_VERSION), EncryptionLevel.Initial, null,
                Role.Server, mock(TlsServerEngine.class), mock(Logger.class), mock(Sender.class));
        int extensionLength = 1100;
        String fakeExtensionType = "fa7e";
        String largeExtension = fakeExtensionType + String.format("%04x", extensionLength) + "00".repeat(extensionLength);
        byte[] ch = new ClientHelloBuilder().withExtension(largeExtension).buildBinary();

        // When
        withSplittedMessage(ch, 1183, (offset, part) -> {
            try {
                cryptoStream.add(new CryptoFrame(QUIC_VERSION, offset, part));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Then
        assertThat(cryptoStream.getTlsMessages())
                .isNotEmpty()
                .hasOnlyElementsOfType(ClientHello.class);
    }

    @Test
    void veryLargeCertificateMessageIsAccepted() throws Exception {
        cryptoStream = new CryptoStream(new VersionHolder(QUIC_VERSION), EncryptionLevel.Handshake, null,
                Role.Client, mock(TlsServerEngine.class), mock(Logger.class), mock(Sender.class));

        byte[] cm = new CertificateMessageBuilder()
                .withNumberOfCertificates(10)
                .buildBinary();

        // When
        withSplittedMessage(cm, 1183, (offset, part) -> {
            try {
                cryptoStream.add(new CryptoFrame(QUIC_VERSION, offset, part));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Then
        assertThat(cryptoStream.getTlsMessages())
                .isNotEmpty()
                .hasOnlyElementsOfType(CertificateMessage.class);
    }

    @Test
    void normalFinishedMessageIsAccepted() throws Exception {
        // Given
        cryptoStream = new CryptoStream(new VersionHolder(QUIC_VERSION), EncryptionLevel.Handshake, null,
                Role.Client, mock(TlsServerEngine.class), mock(Logger.class), mock(Sender.class));
        byte[] normalFinishedMessage = new FinishedMessage(new byte[384 / 8]).getBytes();

        // When
        assertThatCode(() ->
                // When
                cryptoStream.add(new CryptoFrame(QUIC_VERSION, normalFinishedMessage))
                // Then
        ).doesNotThrowAnyException();
    }

    private List<byte[]> splitMessage(byte[] message, int maxFrameSize) {
        int numberOfFrames = (int) Math.ceil((double) message.length / maxFrameSize);
        return Arrays.stream(new int[numberOfFrames]).mapToObj(i -> {
            int offset = i * maxFrameSize;
            int length = Math.min(maxFrameSize, message.length - offset);
            return Arrays.copyOfRange(message, offset, offset + length);
        }).collect(Collectors.toList());
    }

    private void withSplittedMessage(byte[] message, int maxFrameSize, BiConsumer<Integer, byte[]> consumer) {
        int numberOfFrames = (int) Math.ceil((double) message.length / maxFrameSize);
        for (int i = 0; i < numberOfFrames; i++) {
            int offset = i * maxFrameSize;
            int length = Math.min(maxFrameSize, message.length - offset);
            byte[] data = Arrays.copyOfRange(message, offset, offset + length);
            consumer.accept(offset, data);
        };
    }

    private void setParseFunction(Function<ByteBuffer, HandshakeMessage> parseFunction) throws Exception {
        when(messageParser.parseAndProcessHandshakeMessage(any(ByteBuffer.class), any(TlsClientEngine.class), any(ProtectionKeysType.class))).thenAnswer(new Answer<HandshakeMessage>() {
            @Override
            public HandshakeMessage answer(InvocationOnMock invocation) throws Throwable {
                ByteBuffer buffer = invocation.getArgument(0);
                return parseFunction.apply(buffer);
            }
        });
    }

    private byte[] convertToMsgBytes(String content) {
        return convertToMsgBytes(0, content);
    }

    private byte[] convertToMsgBytes(int type, String content) {
        byte[] bytes = new byte[content.getBytes().length + 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(content.getBytes().length);
        buffer.put(content.getBytes());
        buffer.rewind();
        buffer.put((byte) type);
        return bytes;
    }

    static class MockTlsMessage extends HandshakeMessage {
        private final int type;
        private final String contents;

        public MockTlsMessage(int type, String contents) {
            this.type = type;
            this.contents = contents;
        }

        public MockTlsMessage(String contents) {
            this.type = 0;
            this.contents = contents;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MockTlsMessage that = (MockTlsMessage) o;
            return Objects.equals(contents, that.contents);
        }

        @Override
        public int hashCode() {
            return Objects.hash(contents);
        }

        @Override
        public String toString() {
            return "Message: " + contents;
        }

        @Override
        public TlsConstants.HandshakeType getType() {
            return Arrays.stream(TlsConstants.HandshakeType.values()).filter(v -> v.value == this.type).findFirst().get();
        }

        @Override
        public byte[] getBytes() {
            return new byte[0];
        }
    }

}
