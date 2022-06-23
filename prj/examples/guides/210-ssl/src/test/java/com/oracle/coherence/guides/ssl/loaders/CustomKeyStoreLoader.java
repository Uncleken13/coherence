/*
 * Copyright (c) 2000, 2022, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.guides.ssl.loaders;

import com.tangosol.net.ssl.AbstractKeyStoreLoader;
import com.tangosol.net.ssl.KeyStoreLoader;

import com.tangosol.util.Resources;

import java.io.IOException;
import java.io.InputStream;

// #tag::test[]
/**
 * An example implementation of a {@link KeyStoreLoader} which loads a key store from a file.
 *
 * @author Tim Middleton 2022.06.16
 */
public class CustomKeyStoreLoader
        extends AbstractKeyStoreLoader
    {
    public CustomKeyStoreLoader(String url)
        {
        super(url);
        }

    @Override
    protected InputStream getInputStream() throws IOException
        {
        try
            {
            return Resources.findInputStream(m_sName);
            }
        catch (IOException e)
            {
            throw new IOException(e);
            }
        }
    }
// #end::test[]
