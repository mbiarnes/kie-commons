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

import org.kie.commons.io.IOService;
import org.kie.commons.java.nio.IOException;
import org.kie.commons.java.nio.file.FileSystem;
import org.kie.commons.java.nio.file.FileVisitResult;
import org.kie.commons.java.nio.file.Path;
import org.kie.commons.java.nio.file.SimpleFileVisitor;
import org.kie.commons.java.nio.file.attribute.BasicFileAttributes;
import org.kie.commons.java.nio.file.attribute.FileAttribute;
import org.kie.commons.java.nio.file.attribute.FileAttributeView;
import org.kie.kieora.engine.MetaIndexEngine;

import static org.kie.commons.java.nio.file.Files.*;
import static org.kie.commons.validation.PortablePreconditions.*;
import static org.kie.kieora.io.KObjectUtil.*;

/**
 *
 */
public final class BatchIndex {

    private final MetaIndexEngine                      indexEngine;
    private final IOService                            ioService;
    private final Class<? extends FileAttributeView>[] views;

    public BatchIndex( final MetaIndexEngine indexEngine,
                       final IOService ioService,
                       final Class<? extends FileAttributeView>... views ) {
        this.indexEngine = checkNotNull( "indexEngine", indexEngine );
        this.ioService = checkNotNull( "ioService", ioService );
        this.views = views;
    }

    public void runAsync( final FileSystem fs ) {
        new Thread() {
            public void run() {
                for ( final Path root : fs.getRootDirectories() ) {
                    BatchIndex.this.run( root );
                }
            }
        }.start();
    }

    public void runAsync( final Path root ) {
        new Thread() {
            public void run() {
                BatchIndex.this.run( root );
            }
        }.start();
    }

    public void run( final Path root ) {
        indexEngine.startBatchMode();
        walkFileTree( checkNotNull( "root", root ), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile( final Path file,
                                              final BasicFileAttributes attrs ) throws IOException {

                checkNotNull( "file", file );
                checkNotNull( "attrs", attrs );

                if ( !file.getFileName().toString().startsWith( "." ) ) {

                    for ( final Class<? extends FileAttributeView> view : views ) {
                        ioService.getFileAttributeView( file, view );
                    }

                    final FileAttribute<?>[] allAttrs = ioService.convert( ioService.readAttributes( file ) );
                    indexEngine.index( toKObject( file, allAttrs ) );
                }

                return FileVisitResult.CONTINUE;
            }
        } );
        indexEngine.commit();
    }

    public void dispose() {
        indexEngine.dispose();
    }

}
