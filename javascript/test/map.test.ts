import Fury, { TypeDescription, InternalSerializerType } from '@furyjs/fury';
import {describe, expect, test} from '@jest/globals';

describe('map', () => {
  test('should map work', () => {
    const hps = process.env.enableHps ? require('@furyjs/hps') : null;
    const fury = new Fury({ hps });    
    const input = fury.serialize(new Map([["foo", "bar"], ["foo2", "bar2"]]));
    const result = fury.deserialize(
        input
    );
    expect(result).toEqual(new Map([["foo", "bar"],["foo2", "bar2"]]))
  });
});


