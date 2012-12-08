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

package org.kie.commons.java.nio.fs.file;

import java.io.File;

import org.kie.commons.java.nio.IOException;
import org.kie.commons.java.nio.base.AbstractBasicFileAttributeView;
import org.kie.commons.java.nio.base.BasicFileAttributesImpl;
import org.kie.commons.java.nio.base.FileTimeImpl;
import org.kie.commons.java.nio.base.LazyAttrLoader;
import org.kie.commons.java.nio.file.Path;
import org.kie.commons.java.nio.file.attribute.BasicFileAttributes;

import static org.kie.commons.validation.PortablePreconditions.*;

/**
 *
 */
public class SimpleBasicFileAttributeView extends AbstractBasicFileAttributeView {

    private final Path path;
    private BasicFileAttributes attrs = null;

    public SimpleBasicFileAttributeView( final Path path ) {
        this.path = checkNotNull( "path", path );
    }

    @Override
    public <T extends BasicFileAttributes> T readAttributes() throws IOException {
        if ( attrs == null ) {
            final File file = path.toFile();
            this.attrs = new BasicFileAttributesImpl( null, new FileTimeImpl( file.lastModified() ), null, null, new LazyAttrLoader<Long>() {
                private Long size = null;

                @Override
                public Long get() {
                    if ( size == null ) {
                        size = file.length();
                    }

                    return size;
                }
            }, file.isFile(), file.isDirectory() );
        }
        return (T) attrs;
    }
}