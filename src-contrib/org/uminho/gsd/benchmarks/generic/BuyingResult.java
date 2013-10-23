/*
 * *********************************************************************
 * Copyright (c) 2010 Pedro Gomes and Universidade do Minho.
 * All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ********************************************************************
 */

package org.uminho.gsd.benchmarks.generic;

public enum BuyingResult {
    BOUGHT, //Product bought
    NOT_AVAILABLE, //not available, the product has no stock, so you cant buy it
    OUT_OF_STOCK, //bought product, but there is no stock to deliver the product
    DOES_NOT_EXIST, //debug result, the item does not exist
    CANT_COMFIRM  //debug result, when we can't see the item stock after being bought

}
