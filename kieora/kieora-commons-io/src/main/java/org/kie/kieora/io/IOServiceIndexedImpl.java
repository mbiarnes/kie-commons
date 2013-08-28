/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kieora.io;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.kie.commons.data.Pair;
import org.kie.commons.io.FileSystemType;
import org.kie.commons.io.impl.IOServiceDotFileImpl;
import org.kie.commons.java.nio.IOException;
import org.kie.commons.java.nio.base.FileSystemId;
import org.kie.commons.java.nio.base.Properties;
import org.kie.commons.java.nio.channels.SeekableByteChannel;
import org.kie.commons.java.nio.file.AtomicMoveNotSupportedException;
import org.kie.commons.java.nio.file.CopyOption;
import org.kie.commons.java.nio.file.DirectoryNotEmptyException;
import org.kie.commons.java.nio.file.FileAlreadyExistsException;
import org.kie.commons.java.nio.file.FileSystem;
import org.kie.commons.java.nio.file.FileSystemAlreadyExistsException;
import org.kie.commons.java.nio.file.FileSystemNotFoundException;
import org.kie.commons.java.nio.file.NoSuchFileException;
import org.kie.commons.java.nio.file.OpenOption;
import org.kie.commons.java.nio.file.Path;
import org.kie.commons.java.nio.file.ProviderNotFoundException;
import org.kie.commons.java.nio.file.StandardWatchEventKind;
import org.kie.commons.java.nio.file.WatchEvent;
import org.kie.commons.java.nio.file.WatchService;
import org.kie.commons.java.nio.file.attribute.FileAttribute;
import org.kie.commons.java.nio.file.attribute.FileAttributeView;
import org.kie.kieora.engine.MetaIndexEngine;

import static org.kie.commons.java.nio.base.dotfiles.DotFileUtils.*;
import static org.kie.commons.validation.Preconditions.*;
import static org.kie.kieora.io.KObjectUtil.*;

public class IOServiceIndexedImpl extends IOServiceDotFileImpl {

    private final MetaIndexEngine indexEngine;
    private final BatchIndex batchIndex;

    private final Class<? extends FileAttributeView>[] views;
    private final Set<FileSystem> indexedFSs = new HashSet<FileSystem>();
    private final ThreadGroup threadGroup = new ThreadGroup( "IOServiceIndexing" );

    public IOServiceIndexedImpl( final MetaIndexEngine indexEngine,
                                 Class<? extends FileAttributeView>... views ) {
        this.indexEngine = checkNotNull( "indexEngine", indexEngine );
        this.batchIndex = new BatchIndex( indexEngine, this, views );
        this.views = views;
    }

    @Override
    public FileSystem getFileSystem( final URI uri )
            throws IllegalArgumentException, FileSystemNotFoundException,
            ProviderNotFoundException, SecurityException {
        try {
            final FileSystem fs = super.getFileSystem( uri );
            indexIfFresh( fs );
            return fs;
        } catch ( final IllegalArgumentException ex ) {
            throw ex;
        } catch ( final FileSystemNotFoundException ex ) {
            throw ex;
        } catch ( final ProviderNotFoundException ex ) {
            throw ex;
        } catch ( final SecurityException ex ) {
            throw ex;
        }
    }

    @Override
    public FileSystem newFileSystem( final URI uri,
                                     final Map<String, ?> env,
                                     final FileSystemType type )
            throws IllegalArgumentException, FileSystemAlreadyExistsException,
            ProviderNotFoundException, IOException, SecurityException {
        try {
            final FileSystem fs = super.newFileSystem( uri, env, type );
            index( fs );
            setupWatchService( fs );
            return fs;
        } catch ( final IllegalArgumentException ex ) {
            throw ex;
        } catch ( final FileSystemAlreadyExistsException ex ) {
            throw ex;
        } catch ( final ProviderNotFoundException ex ) {
            throw ex;
        } catch ( final IOException ex ) {
            throw ex;
        } catch ( final SecurityException ex ) {
            throw ex;
        }
    }

    private void setupWatchService( final FileSystem fs ) {
        final WatchService ws = fs.newWatchService();
        new Thread( threadGroup, "IOService(WatchService[" + ( (FileSystemId) fs ).id() + "])" ) {
            @Override
            public void run() {
                while ( true ) {
                    final List<WatchEvent<?>> events = ws.take().pollEvents();
                    for ( WatchEvent object : events ) {
                        if ( object.kind() == StandardWatchEventKind.ENTRY_MODIFY
                                || object.kind() == StandardWatchEventKind.ENTRY_CREATE ) {

                            final Path path = (Path) object.context();

                            if ( !path.getFileName().toString().startsWith( "." ) ) {

                                for ( final Class<? extends FileAttributeView> view : views ) {
                                    getFileAttributeView( path, view );
                                }

                                final FileAttribute<?>[] allAttrs = convert( readAttributes( path ) );
                                indexEngine.index( toKObject( path, allAttrs ) );
                            }
                        }
                        if ( object.kind() == StandardWatchEventKind.ENTRY_RENAME ) {
                            Pair<Path, Path> pair = (Pair<Path, Path>) object.context();
                            indexEngine.rename( toKObjectKey( pair.getK1() ), toKObjectKey( pair.getK2() ) );
                        }
                        if ( object.kind() == StandardWatchEventKind.ENTRY_DELETE ) {
                            final Path path = (Path) object.context();
                            indexEngine.delete( toKObjectKey( path ) );
                        }
                    }
                }
            }
        }.start();
    }

    @Override
    public synchronized void delete( final Path path )
            throws IllegalArgumentException, NoSuchFileException, DirectoryNotEmptyException,
            IOException, SecurityException {
        super.delete( path );
        indexEngine.delete( toKObjectKey( path ) );
    }

    @Override
    public synchronized boolean deleteIfExists( final Path path )
            throws IllegalArgumentException, DirectoryNotEmptyException, IOException, SecurityException {
        final boolean result = super.deleteIfExists( path );
        if ( result ) {
            indexEngine.delete( toKObjectKey( path ) );
        }
        return result;
    }

    @Override
    public synchronized SeekableByteChannel newByteChannel( final Path path,
                                                            final Set<? extends OpenOption> options,
                                                            final FileAttribute<?>... attrs )
            throws IllegalArgumentException, UnsupportedOperationException,
            FileAlreadyExistsException, IOException, SecurityException {
        checkNotNull( "path", path );

        final SeekableByteChannel byteChannel = super.newByteChannel( path, options, attrs );

        return new SeekableByteChannel() {
            @Override
            public long position() throws IOException {
                return byteChannel.position();
            }

            @Override
            public SeekableByteChannel position( final long newPosition ) throws IOException {
                return byteChannel.position( newPosition );
            }

            @Override
            public long size() throws IOException {
                return byteChannel.size();
            }

            @Override
            public SeekableByteChannel truncate( final long size ) throws IOException {
                return byteChannel.truncate( size );
            }

            @Override
            public int read( final ByteBuffer dst ) throws java.io.IOException {
                return byteChannel.read( dst );
            }

            @Override
            public int write( final ByteBuffer src ) throws java.io.IOException {
                return byteChannel.write( src );
            }

            @Override
            public boolean isOpen() {
                return byteChannel.isOpen();
            }

            @Override
            public void close() throws java.io.IOException {
                byteChannel.close();
                //force load attrs
                for ( final Class<? extends FileAttributeView> view : views ) {
                    IOServiceIndexedImpl.super.getFileAttributeView( path, view );
                }

                final FileAttribute<?>[] allAttrs = convert( IOServiceIndexedImpl.this.readAttributes( path ) );

                indexEngine.index( toKObject( path, allAttrs ) );
            }
        };
    }

    @Override
    public synchronized Path copy( final Path source,
                                   final Path target,
                                   final CopyOption... options )
            throws UnsupportedOperationException, FileAlreadyExistsException,
            DirectoryNotEmptyException, IOException, SecurityException {
        final Path result = super.copy( source, target, options );

        final Properties properties = new Properties();
        if ( exists( dot( target ) ) ) {
            properties.load( newInputStream( dot( target ) ) );
        }

        indexEngine.index( toKObject( target, convert( properties ) ) );

        return result;
    }

    @Override
    public synchronized Path move( final Path source,
                                   final Path target,
                                   final CopyOption... options )
            throws UnsupportedOperationException, FileAlreadyExistsException,
            DirectoryNotEmptyException, AtomicMoveNotSupportedException, IOException, SecurityException {
        final Path result = super.move( source, target, options );

        indexEngine.rename( toKObjectKey( source ), toKObjectKey( target ) );

        return result;
    }

    private void indexIfFresh( final FileSystem fs ) {
        if ( indexEngine.freshIndex() && !indexedFSs.contains( fs ) ) {
            index( fs );
        }
    }

    private Path index( final Path path ) {
        for ( final Class<? extends FileAttributeView> view : views ) {
            getFileAttributeView( path, view );
        }

        final FileAttribute<?>[] allAttrs = convert( readAttributes( path ) );

        indexEngine.index( toKObject( path, allAttrs ) );

        return path;
    }

    public Path write( final Path path,
                       final byte[] bytes,
                       final OpenOption... options )
            throws IOException, UnsupportedOperationException, SecurityException {
        return index( super.write( path, bytes, options ) );
    }

    public Path write( final Path path,
                       final byte[] bytes,
                       final Map<String, ?> attrs,
                       final OpenOption... options )
            throws IOException, UnsupportedOperationException, SecurityException {
        return index( super.write( path, bytes, attrs, options ) );
    }

    public Path write( final Path path,
                       final byte[] bytes,
                       final Set<? extends OpenOption> options,
                       final FileAttribute<?>... attrs )
            throws IllegalArgumentException, IOException, UnsupportedOperationException {
        return index( super.write( path, bytes, options, attrs ) );
    }

    public Path write( final Path path,
                       final Iterable<? extends CharSequence> lines,
                       final Charset cs,
                       final OpenOption... options )
            throws IllegalArgumentException, IOException, UnsupportedOperationException, SecurityException {
        return index( super.write( path, lines, cs, options ) );
    }

    public Path write( final Path path,
                       final String content,
                       final OpenOption... options )
            throws IllegalArgumentException, IOException, UnsupportedOperationException {
        return index( super.write( path, content, options ) );
    }

    public Path write( final Path path,
                       final String content,
                       final Charset cs,
                       final OpenOption... options )
            throws IllegalArgumentException, IOException, UnsupportedOperationException {
        return index( super.write( path, content, cs, options ) );
    }

    public Path write( final Path path,
                       final String content,
                       final Set<? extends OpenOption> options,
                       final FileAttribute<?>... attrs )
            throws IllegalArgumentException, IOException, UnsupportedOperationException {
        return index( super.write( path, content, options, attrs ) );
    }

    public Path write( final Path path,
                       final String content,
                       final Charset cs,
                       final Set<? extends OpenOption> options,
                       final FileAttribute<?>... attrs )
            throws IllegalArgumentException, IOException, UnsupportedOperationException {
        return index( super.write( path, content, cs, options, attrs ) );
    }

    public Path write( final Path path,
                       final String content,
                       final Map<String, ?> attrs,
                       final OpenOption... options )
            throws IllegalArgumentException, IOException, UnsupportedOperationException {
        return index( super.write( path, content, attrs, options ) );
    }

    public Path write( final Path path,
                       final String content,
                       final Charset cs,
                       final Map<String, ?> attrs,
                       final OpenOption... options )
            throws IllegalArgumentException, IOException, UnsupportedOperationException {
        return index( super.write( path, content, cs, attrs, options ) );
    }

    public OutputStream newOutputStream( final Path path,
                                         final OpenOption... options )
            throws IllegalArgumentException, UnsupportedOperationException,
            IOException, SecurityException {
        final OutputStream out = super.newOutputStream( path, options );
        return new OutputStream() {
            @Override
            public void write( final int b ) throws java.io.IOException {
                out.write( b );
            }

            @Override
            public void close() throws java.io.IOException {
                out.close();
                index( path );
            }
        };
    }

    public BufferedWriter newBufferedWriter( final Path path,
                                             final Charset cs,
                                             final OpenOption... options )
            throws IllegalArgumentException, IOException, UnsupportedOperationException, SecurityException {
        return new BufferedWriter( super.newBufferedWriter( path, cs, options ) ) {
            @Override
            public void close() throws java.io.IOException {
                super.close();
                index( path );
            }
        };
    }

    public Path createFile( final Path path,
                            final FileAttribute<?>... attrs )
            throws IllegalArgumentException, UnsupportedOperationException,
            FileAlreadyExistsException, IOException, SecurityException {
        return index( super.createFile( path, attrs ) );

    }

    public Path setAttributes( final Path path,
                               final FileAttribute<?>... attrs )
            throws UnsupportedOperationException, IllegalArgumentException,
            ClassCastException, IOException, SecurityException {
        return index( super.setAttributes( path, attrs ) );
    }

    public Path setAttributes( final Path path,
                               final Map<String, Object> attrs )
            throws UnsupportedOperationException, IllegalArgumentException,
            ClassCastException, IOException, SecurityException {
        return index( super.setAttributes( path, attrs ) );
    }

    public Path setAttribute( final Path path,
                              final String attribute,
                              final Object value )
            throws UnsupportedOperationException, IllegalArgumentException,
            ClassCastException, IOException, SecurityException {
        return index( super.setAttribute( path, attribute, value ) );

    }

    private void index( final FileSystem fs ) {
        indexedFSs.add( fs );
        batchIndex.runAsync( fs );
    }

}
